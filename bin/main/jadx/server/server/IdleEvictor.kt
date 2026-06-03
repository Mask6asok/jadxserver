package jadx.server.server

import jadx.server.engine.DecompilerEngine
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class IdleEvictor(
    private val enginePool: EnginePool,
    private val engine: DecompilerEngine,
    private val idleTimeout: Duration,
    private val cleanupInterval: Duration
) {
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
        } catch (e: Exception) {
            logger.error("Error during eviction cycle: {}", e.message, e)
        }
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
