package jadx.server.server

import jadx.server.engine.DecompilerEngine
import jadx.server.engine.EngineInstance
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class IdleEvictor(
    private val enginePool: EnginePool,
    private val engine: DecompilerEngine,
    private val memoryGovernor: MemoryGovernor,
    private val idleTimeout: Duration,
    private val cleanupInterval: Duration,
    private val taskManager: TaskManager,
    private val fileIndex: FileIndex,
    private val memoryCleanupInterval: Duration = Duration.ofHours(1),
    private val analysisActivity: AnalysisActivityCoordinator = AnalysisActivityCoordinator(),
    private val gcAction: () -> Unit = { System.gc() },
    private val now: () -> Instant = Instant::now,
) {
    companion object {
        private val UNLOAD_IDLE_THRESHOLD: Duration = Duration.ofSeconds(60)
        private val UNLOAD_COOLDOWN: Duration = Duration.ofMinutes(10)
    }

    private val logger = LoggerFactory.getLogger(IdleEvictor::class.java)
    private val lifecycleLock = ReentrantLock()
    private var scheduler: ScheduledExecutorService? = null
    private var shuttingDown = false
    private var nextMemoryCleanupAt: Instant? = null

    init {
        require(!cleanupInterval.isNegative && !cleanupInterval.isZero) {
            "cleanupInterval must be greater than zero"
        }
        require(!memoryCleanupInterval.isNegative) {
            "memoryCleanupInterval must be zero or greater"
        }
    }

    fun start() {
        lifecycleLock.lock()
        try {
            if (scheduler != null) {
                return
            }
            shuttingDown = false
            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "idle-evictor").apply { isDaemon = true }
            }
            scheduler = exec
            nextMemoryCleanupAt = if (memoryCleanupInterval.isZero) {
                null
            } else {
                now().plusMillis(memoryCleanupInterval.toMillis())
            }
            val intervalMs = cleanupInterval.toMillis().coerceAtLeast(1L)
            exec.scheduleAtFixedRate(::runCycle, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
            logger.info(
                "IdleEvictor started: interval={}s, timeout={}s, memoryCleanupInterval={}s",
                cleanupInterval.toSeconds(),
                idleTimeout.toSeconds(),
                memoryCleanupInterval.toSeconds(),
            )
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun runCycle() {
        lifecycleLock.lock()
        try {
            if (!shuttingDown) {
                evict()
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun evict() {
        try {
            unloadEligibleIdleInstances()

            val evicted = enginePool.evict(idleTimeout)
            if (evicted.isNotEmpty()) {
                logger.info("Evicting {} idle engine instance(s)", evicted.size)
                for (instance in evicted) {
                    try {
                        engine.closeWithoutGc(instance)
                        logger.debug("Closed evicted instance: {}", instance.instanceId)
                    } catch (e: Exception) {
                        logger.warn("Error closing evicted instance {}: {}", instance.instanceId, e.message)
                    }
                }
                // Batch eviction frees large jadx heaps; prompt GC to return memory to OS
                gcAction()
            }

            taskManager.pruneOld()
            fileIndex.enforceMaxEntries()
            runDueMemoryCleanup()
        } catch (e: Exception) {
            logger.error("Error during eviction cycle: {}", e.message, e)
        }
    }

    internal fun runOnceForTest() {
        lifecycleLock.lock()
        try {
            if (!shuttingDown) {
                evict()
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    internal fun runMemoryCleanupOnceForTest(): Boolean {
        lifecycleLock.lock()
        try {
            if (shuttingDown) {
                return false
            }
            return runMemoryCleanup()
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun runDueMemoryCleanup() {
        val dueAt = nextMemoryCleanupAt ?: return
        val current = now()
        if (current.isBefore(dueAt)) {
            return
        }
        if (runMemoryCleanup()) {
            nextMemoryCleanupAt = current.plus(memoryCleanupInterval)
        }
    }

    private fun runMemoryCleanup(): Boolean {
        val maintenanceLease = analysisActivity.tryEnterMaintenance()
        if (maintenanceLease == null) {
            logger.debug("Skipping periodic memory cleanup: analysis activity is still running")
            return false
        }

        try {
            val detached = enginePool.beginMemoryCleanup()
            if (detached == null) {
                logger.debug("Skipping periodic memory cleanup: engine pool is busy or opening an instance")
                return false
            }

            val before = memoryGovernor.snapshot()
            try {
                logger.info("Starting periodic memory cleanup: {} idle engine instance(s)", detached.size)
                for (instance in detached) {
                    try {
                        engine.closeWithoutGc(instance)
                        logger.debug("Closed memory-cleanup instance: {}", instance.instanceId)
                    } catch (e: Exception) {
                        logger.warn("Error closing memory-cleanup instance {}: {}", instance.instanceId, e.message)
                    }
                }
                gcAction()
                val after = memoryGovernor.snapshot()
                logger.info(
                    "Periodic memory cleanup completed: closed={}, heapUsedMb={} -> {}, heapCommittedMb={} -> {}",
                    detached.size,
                    before.usedBytes / (1024 * 1024),
                    after.usedBytes / (1024 * 1024),
                    before.totalBytes / (1024 * 1024),
                    after.totalBytes / (1024 * 1024),
                )
                return true
            } finally {
                enginePool.endMemoryCleanup()
            }
        } finally {
            maintenanceLease.close()
        }
    }

    private fun unloadEligibleIdleInstances() {
        val now = Instant.now()
        val pressure = memoryGovernor.allowHeavyOperation().level
        val unloadOnPressure = pressure == MemoryPressureLevel.HARD || pressure == MemoryPressureLevel.CRITICAL

        val candidates = enginePool.listInstances()
            .filter { it.state == PoolState.Idle }
            .filter { info ->
                if (isInUnloadCooldown(info.lastUnloadAt, now)) {
                    return@filter false
                }
                val idleFor = enginePool.metadataFor(info.asLookupInstance())
                    ?.let { Duration.between(it.lastReleased, now) }
                    ?: Duration.ZERO
                val idleLongEnoughAndEligible = info.unloadEligible && idleFor >= UNLOAD_IDLE_THRESHOLD
                idleLongEnoughAndEligible || unloadOnPressure
            }

        if (candidates.isEmpty()) {
            return
        }

        var unloadedCount = 0
        for (candidate in candidates) {
            val instance = candidate.asLookupInstance()
            try {
                if (enginePool.unload(instance)) {
                    unloadedCount++
                    logger.debug("Unloaded idle instance: {}", candidate.instanceId)
                }
            } catch (e: Exception) {
                logger.warn("Error unloading idle instance {}: {}", candidate.instanceId, e.message)
            }
        }

        if (unloadedCount > 0) {
            logger.info("Unloaded {} idle engine instance(s) before eviction", unloadedCount)
        }
    }

    private fun isInUnloadCooldown(lastUnloadAt: Instant?, now: Instant): Boolean {
        return lastUnloadAt != null && Duration.between(lastUnloadAt, now) < UNLOAD_COOLDOWN
    }

    private fun InstanceInfo.asLookupInstance(): EngineInstance {
        return EngineInstance(
            instanceId = instanceId,
            engineName = engine.name,
            fileHash = fileHash,
            openedAt = openedAt,
            state = Unit
        )
    }

    fun shutdown() {
        val exec: ScheduledExecutorService
        lifecycleLock.lock()
        try {
            val current = scheduler
            if (current == null) {
                return
            }
            shuttingDown = true
            exec = current
            scheduler = null
            nextMemoryCleanupAt = null
        } finally {
            lifecycleLock.unlock()
        }
        exec.shutdown()
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logger.info("IdleEvictor shut down")
    }
}
