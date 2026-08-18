package jadx.server.server

import jadx.server.config.ServerConfig
import jadx.server.engine.DecompilerEngine
import jadx.server.engine.JadxEngine
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class ServerState(
    val config: ServerConfig,
    val engine: DecompilerEngine = JadxEngine(),
    val memoryGovernor: MemoryGovernor = MemoryGovernor()
) {
    val fileIndex: FileIndex
    val enginePool: EnginePool
    val sessionManager = SessionManager()
    val taskManager = TaskManager()
    val analysisActivity = AnalysisActivityCoordinator()
    val idleEvictor: IdleEvictor
    val startedAt: Instant = Instant.now()

    init {
        Files.createDirectories(config.uploadDir)
        fileIndex = FileIndex.loadFromUploadDir(config.uploadDir)
        fileIndex.setMaxEntries(config.maxCachedApks)
        val resolvedMaxTotal = if (config.maxInstances > 0) {
            config.maxInstances
        } else if (Runtime.getRuntime().maxMemory() <= LOW_HEAP_PROFILE_BYTES) {
            1
        } else {
            maxOf(1, minOf(Runtime.getRuntime().availableProcessors() / 4, 2))
        }
        val poolConfig = PoolConfig(
            maxTotal = resolvedMaxTotal,
            maxPerFile = config.maxPerFile.coerceAtMost(resolvedMaxTotal),
            idleTimeout = config.idleTimeout
        )
        enginePool = EnginePool(poolConfig, engine, fileIndex)
        idleEvictor = IdleEvictor(
            enginePool = enginePool,
            engine = engine,
            memoryGovernor = memoryGovernor,
            idleTimeout = config.idleTimeout,
            cleanupInterval = config.cleanupInterval,
            memoryCleanupInterval = config.memoryCleanupInterval,
            taskManager = taskManager,
            fileIndex = fileIndex,
            analysisActivity = analysisActivity,
        )
        idleEvictor.start()
    }

    fun shutdown() {
        idleEvictor.shutdown()
        analysisActivity.withMaintenance {
            val detached = enginePool.beginMemoryCleanup()
            if (detached != null) {
                try {
                    detached.forEach { engine.closeWithoutGc(it) }
                    if (detached.isNotEmpty()) {
                        System.gc()
                    }
                } finally {
                    enginePool.endMemoryCleanup()
                }
            } else {
                val evicted = enginePool.evict(Duration.ZERO)
                evicted.forEach { engine.closeWithoutGc(it) }
                if (evicted.isNotEmpty()) {
                    System.gc()
                }
            }
        }
        fileIndex.persist(config.uploadDir)
    }

    private companion object {
        const val LOW_HEAP_PROFILE_BYTES = 12L * 1024L * 1024L * 1024L
    }
}
