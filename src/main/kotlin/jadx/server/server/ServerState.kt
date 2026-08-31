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
    /** Shared immutable per-APK metadata/source index used by SHARED runtime mode. */
    val apkRuntimeRegistry = ApkRuntimeRegistry()
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
            maxPerFile = effectiveMaxPerFile(resolvedMaxTotal),
            idleTimeout = config.idleTimeout,
            singleFlightPreparation = config.runtimeMode == jadx.server.config.RuntimeMode.SHARED,
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
        apkRuntimeRegistry.clear()
    }

    private companion object {
        const val LOW_HEAP_PROFILE_BYTES = 12L * 1024L * 1024L * 1024L
    }

    private fun effectiveMaxPerFile(resolvedMaxTotal: Int): Int {
        val configured = if (config.runtimeMode == jadx.server.config.RuntimeMode.SHARED) {
            // O1 deliberately bounds the mutable graph to one or two copies;
            // larger values recreate the memory failure mode this topology is
            // intended to avoid, even if a caller passes an unsafe CLI value.
            config.sharedEngineSlots.coerceIn(1, 2)
        } else {
            config.maxPerFile
        }
        return configured.coerceAtLeast(1).coerceAtMost(resolvedMaxTotal)
    }
}
