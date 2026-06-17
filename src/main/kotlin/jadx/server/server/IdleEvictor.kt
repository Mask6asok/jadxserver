package jadx.server.server

import jadx.server.engine.DecompilerEngine
import jadx.server.engine.EngineInstance
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class IdleEvictor(
    private val enginePool: EnginePool,
    private val engine: DecompilerEngine,
    private val memoryGovernor: MemoryGovernor,
    private val idleTimeout: Duration,
    private val cleanupInterval: Duration,
    private val taskManager: TaskManager,
    private val fileIndex: FileIndex
) {
    companion object {
        private val UNLOAD_IDLE_THRESHOLD: Duration = Duration.ofSeconds(60)
        private val UNLOAD_COOLDOWN: Duration = Duration.ofMinutes(10)
    }

    private val logger = LoggerFactory.getLogger(IdleEvictor::class.java)
    private var scheduler: ScheduledExecutorService? = null

    fun start() {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "idle-evictor").apply { isDaemon = true }
        }
        scheduler = exec
        val intervalMs = cleanupInterval.toMillis()
        exec.scheduleAtFixedRate(::evict, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        logger.info("IdleEvictor started: interval={}s, timeout={}s", cleanupInterval.toSeconds(), idleTimeout.toSeconds())
    }

    private fun evict() {
        try {
            unloadEligibleIdleInstances()

            val evicted = enginePool.evict(idleTimeout)
            if (evicted.isNotEmpty()) {
                logger.info("Evicting {} idle engine instance(s)", evicted.size)
                for (instance in evicted) {
                    try {
                        engine.close(instance)
                        logger.debug("Closed evicted instance: {}", instance.instanceId)
                    } catch (e: Exception) {
                        logger.warn("Error closing evicted instance {}: {}", instance.instanceId, e.message)
                    }
                }
                // Batch eviction frees large jadx heaps; prompt GC to return memory to OS
                System.gc()
            }

            taskManager.pruneOld()
            fileIndex.enforceMaxEntries()
        } catch (e: Exception) {
            logger.error("Error during eviction cycle: {}", e.message, e)
        }
    }

    internal fun runOnceForTest() {
        evict()
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
        scheduler?.shutdown()
        try {
            scheduler?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        scheduler = null
        logger.info("IdleEvictor shut down")
    }
}
