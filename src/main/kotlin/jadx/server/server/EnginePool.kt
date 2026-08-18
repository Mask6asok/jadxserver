package jadx.server.server

import jadx.server.engine.DecompilerEngine
import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

data class PoolKey(
    val sessionId: String,
    val fileHash: String
)

enum class PoolState {
    Idle,
    Busy
}

data class PoolEntry(
    val instance: EngineInstance,
    var state: PoolState = PoolState.Idle,
    var lastReleased: Instant = Instant.now(),
    var unloadEligible: Boolean = false,
    var lastUnloadAt: Instant? = null
)

sealed class AcquireResult {
    data class Found(val instance: EngineInstance) : AcquireResult()
    data class NeedSpawn(
        val file: Path,
        val options: EngineOptions,
        val reclaimedInstance: EngineInstance? = null,
    ) : AcquireResult()
    data object Busy : AcquireResult()
    data object Full : AcquireResult()
}

data class PoolConfig(
    val maxTotal: Int,
    val maxPerFile: Int,
    val idleTimeout: Duration
)

data class InstanceInfo(
    val instanceId: String,
    val fileHash: String,
    val sessionId: String,
    val state: PoolState,
    val openedAt: Instant,
    val unloadEligible: Boolean,
    val lastUnloadAt: Instant?
)

class EnginePool(
    private val config: PoolConfig,
    private val engine: DecompilerEngine,
    private val fileIndex: FileIndex
) {
    private val lock = ReentrantLock()
    private val pool = mutableMapOf<PoolKey, MutableList<PoolEntry>>()
    private val instanceToKey = mutableMapOf<String, PoolKey>()
    private var maintenanceInProgress = false
    /**
     * Reservations for engine opens that have been admitted but have not yet
     * reached [insert]. The open happens outside [lock], so counting only
     * [instanceToKey] would let concurrent callers overcommit the pool.
     */
    private val pendingSpawns = mutableMapOf<PoolKey, Int>()

    fun acquire(sessionId: String, fileHash: String, options: EngineOptions): AcquireResult {
        lock.lock()
        try {
            if (maintenanceInProgress) {
                return AcquireResult.Busy
            }
            val key = PoolKey(sessionId, fileHash)
            val entries = pool[key]

            if (entries != null) {
                val idleEntry = entries.find { it.state == PoolState.Idle }
                if (idleEntry != null) {
                    idleEntry.state = PoolState.Busy
                    return AcquireResult.Found(idleEntry.instance)
                }
            }

            val adoptedEntry = adoptIdleInstanceForFile(key)
            if (adoptedEntry != null) {
                adoptedEntry.state = PoolState.Busy
                return AcquireResult.Found(adoptedEntry.instance)
            }

            val pendingForKey = pendingSpawns[key] ?: 0
            if ((entries?.size ?: 0) + pendingForKey >= config.maxPerFile) {
                return AcquireResult.Busy
            }

            val fileEntry = fileIndex.resolve(fileHash)
                ?: return AcquireResult.Full
            val filePath = Path.of(fileEntry.path)

            val totalInstances = instanceToKey.size + pendingSpawns.values.sum()
            val reclaimedInstance = if (totalInstances >= config.maxTotal) {
                detachIdleInstanceForReclaim(key) ?: return AcquireResult.Full
            } else {
                null
            }
            pendingSpawns[key] = pendingForKey + 1
            return AcquireResult.NeedSpawn(filePath, options, reclaimedInstance)
        } finally {
            lock.unlock()
        }
    }

    fun insert(sessionId: String, fileHash: String, instance: EngineInstance) {
        lock.lock()
        try {
            val key = PoolKey(sessionId, fileHash)
            releaseSpawnReservation(key)
            val entries = pool.getOrPut(key) { mutableListOf() }
            entries.add(
                PoolEntry(
                    instance = instance,
                    state = PoolState.Busy,
                    lastReleased = Instant.now()
                )
            )
            instanceToKey[instance.instanceId] = key
        } finally {
            lock.unlock()
        }
    }

    /** Release a spawn reservation when opening the engine failed or was refused. */
    fun cancelSpawn(sessionId: String, fileHash: String) {
        lock.lock()
        try {
            releaseSpawnReservation(PoolKey(sessionId, fileHash))
        } finally {
            lock.unlock()
        }
    }

    fun release(instance: EngineInstance) {
        lock.lock()
        try {
            val key = instanceToKey[instance.instanceId] ?: return
            val entries = pool[key] ?: return
            val entry = entries.find { it.instance.instanceId == instance.instanceId } ?: return
            entry.state = PoolState.Idle
            entry.lastReleased = Instant.now()
        } finally {
            lock.unlock()
        }
    }

    fun markUnloadEligible(instance: EngineInstance, eligible: Boolean = true): Boolean {
        lock.lock()
        try {
            val entry = findEntry(instance) ?: return false
            entry.unloadEligible = eligible
            return true
        } finally {
            lock.unlock()
        }
    }

    fun unload(instance: EngineInstance): Boolean {
        lock.lock()
        try {
            val entry = findEntry(instance) ?: return false
            if (entry.state != PoolState.Idle) {
                return false
            }
            if (!engine.unload(entry.instance)) {
                return false
            }
            entry.unloadEligible = false
            entry.lastUnloadAt = Instant.now()
            return true
        } finally {
            lock.unlock()
        }
    }

    fun metadataFor(instance: EngineInstance): PoolEntry? {
        lock.lock()
        try {
            return findEntry(instance)?.copy()
        } finally {
            lock.unlock()
        }
    }

    fun evict(timeout: Duration): List<EngineInstance> {
        lock.lock()
        try {
            val now = Instant.now()
            val evicted = mutableListOf<EngineInstance>()
            val keysToRemove = mutableListOf<PoolKey>()

            for ((key, entries) in pool) {
                val toRemove = entries.filter { entry ->
                    entry.state == PoolState.Idle &&
                        Duration.between(entry.lastReleased, now) > timeout
                }
                for (entry in toRemove) {
                    entries.remove(entry)
                    instanceToKey.remove(entry.instance.instanceId)
                    evicted.add(entry.instance)
                }
                if (entries.isEmpty()) {
                    keysToRemove.add(key)
                }
            }

            for (key in keysToRemove) {
                pool.remove(key)
            }

            return evicted
        } finally {
            lock.unlock()
        }
    }

    /**
     * Atomically reserve the pool for a maintenance window and detach every
     * idle instance.  The returned instances must be closed by the caller and
     * [endMemoryCleanup] must always be called afterwards.
     *
     * A null result means that an analysis lease or an engine open is active.
     * The check and detach happen under the same lock, so no busy/pending entry
     * can be missed between the two operations.
     */
    fun beginMemoryCleanup(): List<EngineInstance>? {
        lock.lock()
        try {
            if (maintenanceInProgress || pendingSpawns.isNotEmpty() || pool.values.any { entries ->
                    entries.any { it.state == PoolState.Busy }
                }) {
                return null
            }

            maintenanceInProgress = true
            val detached = pool.values
                .asSequence()
                .flatMap { it.asSequence() }
                .filter { it.state == PoolState.Idle }
                .map { it.instance }
                .toList()
            pool.clear()
            instanceToKey.clear()
            return detached
        } finally {
            lock.unlock()
        }
    }

    fun endMemoryCleanup() {
        lock.lock()
        try {
            maintenanceInProgress = false
        } finally {
            lock.unlock()
        }
    }

    fun listInstances(): List<InstanceInfo> {
        lock.lock()
        try {
            val result = mutableListOf<InstanceInfo>()
            for ((key, entries) in pool) {
                for (entry in entries) {
                    result.add(
                        InstanceInfo(
                            instanceId = entry.instance.instanceId,
                            fileHash = key.fileHash,
                            sessionId = key.sessionId,
                            state = entry.state,
                            openedAt = entry.instance.openedAt,
                            unloadEligible = entry.unloadEligible,
                            lastUnloadAt = entry.lastUnloadAt
                        )
                    )
                }
            }
            return result
        } finally {
            lock.unlock()
        }
    }

    fun discard(instance: EngineInstance): EngineInstance? {
        lock.lock()
        try {
            val key = instanceToKey.remove(instance.instanceId) ?: return null
            val entries = pool[key] ?: return null
            val entry = entries.find { it.instance.instanceId == instance.instanceId } ?: return null
            entries.remove(entry)
            if (entries.isEmpty()) {
                pool.remove(key)
            }
            return entry.instance
        } finally {
            lock.unlock()
        }
    }

    fun instanceCount(): Int {
        lock.lock()
        try {
            return instanceToKey.size
        } finally {
            lock.unlock()
        }
    }

    private fun adoptIdleInstanceForFile(targetKey: PoolKey): PoolEntry? {
        for ((key, entries) in pool) {
            if (key == targetKey || key.fileHash != targetKey.fileHash) {
                continue
            }
            val idleEntry = entries.find { it.state == PoolState.Idle } ?: continue
            entries.remove(idleEntry)
            if (entries.isEmpty()) {
                pool.remove(key)
            }

            val targetEntries = pool.getOrPut(targetKey) { mutableListOf() }
            targetEntries.add(idleEntry)
            instanceToKey[idleEntry.instance.instanceId] = targetKey
            return idleEntry
        }
        return null
    }

    private fun findEntry(instance: EngineInstance): PoolEntry? {
        val key = instanceToKey[instance.instanceId] ?: return null
        val entries = pool[key] ?: return null
        return entries.find { it.instance.instanceId == instance.instanceId }
    }

    private fun detachIdleInstanceForReclaim(targetKey: PoolKey): EngineInstance? {
        var reclaimKey: PoolKey? = null
        var reclaimEntry: PoolEntry? = null
        for ((candidateKey, entries) in pool) {
            if (candidateKey.fileHash == targetKey.fileHash) {
                continue
            }
            val idleEntry = entries.firstOrNull { it.state == PoolState.Idle } ?: continue
            reclaimKey = candidateKey
            reclaimEntry = idleEntry
            break
        }

        val key = reclaimKey ?: return null
        val entries = pool[key] ?: return null
        val entry = reclaimEntry ?: return null
        entries.remove(entry)
        instanceToKey.remove(entry.instance.instanceId)
        if (entries.isEmpty()) {
            pool.remove(key)
        }
        return entry.instance
    }

    private fun releaseSpawnReservation(key: PoolKey) {
        val pending = pendingSpawns[key] ?: return
        if (pending <= 1) {
            pendingSpawns.remove(key)
        } else {
            pendingSpawns[key] = pending - 1
        }
    }
}
