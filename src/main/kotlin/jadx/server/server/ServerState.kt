package jadx.server.server

import jadx.server.config.ServerConfig
import jadx.server.engine.DecompilerEngine
import jadx.server.engine.JadxEngine
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class ServerState(val config: ServerConfig) {
    val engine: DecompilerEngine = JadxEngine()
    val fileIndex: FileIndex
    val enginePool: EnginePool
    val sessionManager = SessionManager()
    val taskManager = TaskManager()
    val idleEvictor: IdleEvictor
    val startedAt: Instant = Instant.now()

    init {
        Files.createDirectories(config.uploadDir)
        fileIndex = FileIndex.loadFromUploadDir(config.uploadDir)
        val poolConfig = PoolConfig(
            maxTotal = if (config.maxInstances > 0) config.maxInstances
            else maxOf(1, minOf(Runtime.getRuntime().availableProcessors() / 4, 2)),
            maxPerFile = config.maxPerFile,
            idleTimeout = config.idleTimeout
        )
        enginePool = EnginePool(poolConfig, engine, fileIndex)
        idleEvictor = IdleEvictor(enginePool, engine, config.idleTimeout, config.cleanupInterval)
        idleEvictor.start()
    }

    fun shutdown() {
        idleEvictor.shutdown()
        enginePool.evict(Duration.ZERO).forEach { engine.close(it) }
        fileIndex.persist(config.uploadDir)
    }
}
