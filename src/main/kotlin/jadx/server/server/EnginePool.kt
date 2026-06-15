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
    var lastReleased: Instant = Instant.now()
)

sealed class AcquireResult {
    data class Found(val instance: EngineInstance) : AcquireResult()
    data class NeedSpawn(val file: Path, val options: EngineOptions) : AcquireResult()
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
    val openedAt: Instant
)

class EnginePool(
    private val config: PoolConfig,
    private val engine: DecompilerEngine,
    private val fileIndex: FileIndex
) {
    private val lock = ReentrantLock()
    private val pool = mutableMapOf<PoolKey, MutableList<PoolEntry>>()
    private val instanceToKey = mutableMapOf<String, PoolKey>()

    fun acquire(sessionId: String, fileHash: String, options: EngineOptions): AcquireResult {
        lock.lock()
        try {
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

            if (entries != null && entries.size >= config.maxPerFile) {
                return AcquireResult.Busy
            }

            val totalInstances = instanceToKey.size
            if (totalInstances >= config.maxTotal) {
                return AcquireResult.Full
            }

            val fileEntry = fileIndex.resolve(fileHash)
                ?: return AcquireResult.Full
            val filePath = Path.of(fileEntry.path)

            return AcquireResult.NeedSpawn(filePath, options)
        } finally {
            lock.unlock()
        }
    }

    fun insert(sessionId: String, fileHash: String, instance: EngineInstance) {
        lock.lock()
        try {
            val key = PoolKey(sessionId, fileHash)
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
                            openedAt = entry.instance.openedAt
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
}
