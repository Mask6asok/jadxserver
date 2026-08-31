package jadx.server.server

import jadx.server.engine.DecompilerEngine
import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.Condition
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
    val idleTimeout: Duration,
    /** Prevent duplicate cold opens for one APK until the first request completes. */
    val singleFlightPreparation: Boolean = false,
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

/**
 * A lock-consistent view of the resources currently assigned to one input
 * file. Pending opens are included because they already consume pool
 * capacity, even though the expensive engine construction happens outside
 * the pool lock.
 */
data class FilePoolUsage(
    val fileHash: String,
    val instanceCount: Int,
    val busyCount: Int,
    val idleCount: Int,
    val pendingSpawnCount: Int,
    val sessionCount: Int,
) {
    val reservedCount: Int get() = instanceCount + pendingSpawnCount
}

class EnginePool(
    private val config: PoolConfig,
    private val engine: DecompilerEngine,
    private val fileIndex: FileIndex
) {
    /** Fair lock keeps condition waiters in arrival order for shared-file use. */
    private val lock = ReentrantLock(true)
    private val pool = mutableMapOf<PoolKey, MutableList<PoolEntry>>()
    private val instanceToKey = mutableMapOf<String, PoolKey>()
    private var maintenanceInProgress = false
    private val availabilityChanged: Condition = lock.newCondition()
    private var waitingCount = 0
    private val preparedFiles = mutableSetOf<String>()
    /**
     * Reservations for engine opens that have been admitted but have not yet
     * reached [insert]. The open happens outside [lock], so counting only
     * [instanceToKey] would let concurrent callers overcommit the pool.
     */
    private val pendingSpawns = mutableMapOf<PoolKey, Int>()

    /**
     * Acquire an existing engine or reserve a new one.  Callers handling a
     * snapshot-capable tool may set [allowSpawn] false while a same-file
     * engine is already opening/processing: the request then waits for the
     * existing engine to materialize the missing immutable value instead of
     * creating a second full JADX object graph just for a cache miss.
     */
    fun acquire(
        sessionId: String,
        fileHash: String,
        options: EngineOptions,
        allowSpawn: Boolean = true,
    ): AcquireResult {
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

            val usage = fileUsageLocked(fileHash)
            if (!allowSpawn && usage.reservedCount > 0) {
                return AcquireResult.Busy
            }
            val perFileLimit = if (config.singleFlightPreparation && fileHash !in preparedFiles) {
                1
            } else {
                config.maxPerFile
            }
            if (usage.reservedCount >= perFileLimit) {
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
            pendingSpawns[key] = (pendingSpawns[key] ?: 0) + 1
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
            availabilityChanged.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** Release a spawn reservation when opening the engine failed or was refused. */
    fun cancelSpawn(sessionId: String, fileHash: String) {
        lock.lock()
        try {
            releaseSpawnReservation(PoolKey(sessionId, fileHash))
            availabilityChanged.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Mark the first request for an APK complete.  In shared mode this opens
     * the remaining configured slots only after the cold preparation has
     * produced its snapshot/cache (or deliberately failed and returned).
     */
    fun markPrepared(fileHash: String) {
        lock.lock()
        try {
            if (config.singleFlightPreparation && preparedFiles.add(fileHash)) {
                availabilityChanged.signalAll()
            }
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
            availabilityChanged.signalAll()
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
            availabilityChanged.signalAll()
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

            if (evicted.isNotEmpty()) {
                availabilityChanged.signalAll()
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
            preparedFiles.clear()
            availabilityChanged.signalAll()
            return detached
        } finally {
            lock.unlock()
        }
    }

    fun endMemoryCleanup() {
        lock.lock()
        try {
            maintenanceInProgress = false
            availabilityChanged.signalAll()
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
            availabilityChanged.signalAll()
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

    /**
     * Wait for a pool lifecycle change instead of polling/sleeping.  The
     * caller must retry [acquire] after this method returns because a signal
     * may have been produced by another file's release.
     *
     * The interrupt is deliberately propagated so MCP callers can return a
     * typed WORKER_INTERRUPTED result and never lose their cancellation bit.
     */
    @Throws(InterruptedException::class)
    fun awaitAvailability(timeout: Duration): Boolean {
        if (timeout.isZero || timeout.isNegative) {
            return false
        }
        lock.lockInterruptibly()
        try {
            waitingCount++
            var remaining = timeout.toNanos()
            try {
                while (remaining > 0L) {
                    remaining = availabilityChanged.awaitNanos(remaining)
                    if (remaining > 0L) {
                        return true
                    }
                }
                return false
            } finally {
                waitingCount--
            }
        } finally {
            lock.unlock()
        }
    }

    fun waitingCount(): Int {
        lock.lock()
        try {
            return waitingCount
        } finally {
            lock.unlock()
        }
    }

    fun fileUsage(fileHash: String): FilePoolUsage {
        lock.lock()
        try {
            return fileUsageLocked(fileHash)
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

    private fun fileUsageLocked(fileHash: String): FilePoolUsage {
        var instanceCount = 0
        var busyCount = 0
        var idleCount = 0
        val sessions = mutableSetOf<String>()
        for ((key, entries) in pool) {
            if (key.fileHash != fileHash) {
                continue
            }
            if (entries.isNotEmpty()) {
                sessions.add(key.sessionId)
            }
            instanceCount += entries.size
            busyCount += entries.count { it.state == PoolState.Busy }
            idleCount += entries.count { it.state == PoolState.Idle }
        }
        pendingSpawns.forEach { (key, count) ->
            if (key.fileHash == fileHash && count > 0) {
                sessions.add(key.sessionId)
            }
        }
        return FilePoolUsage(
            fileHash = fileHash,
            instanceCount = instanceCount,
            busyCount = busyCount,
            idleCount = idleCount,
            pendingSpawnCount = pendingSpawns
                .asSequence()
                .filter { (key, _) -> key.fileHash == fileHash }
                .sumOf { (_, count) -> count },
            sessionCount = sessions.size,
        )
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
