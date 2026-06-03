package jadx.server.engine

import jadx.server.config.XrefMode
import jadx.server.mcp.McpToolDef
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

interface DecompilerEngine {
    val name: String
    fun toolSchemas(): List<McpToolDef>
    fun open(file: Path, options: EngineOptions): EngineInstance
    fun close(instance: EngineInstance)
    fun health(instance: EngineInstance): InstanceHealth
}

data class EngineOptions(
    val threads: Int = 4,
    val deobfuscate: Boolean = false,
    val skipResources: Boolean = false,
    val classFilter: String? = null,
    val sourceDir: java.nio.file.Path? = null,
    val xrefMode: XrefMode = XrefMode.JADX
)

data class EngineInstance(
    val instanceId: String = UUID.randomUUID().toString(),
    val engineName: String,
    val fileHash: String,
    val openedAt: Instant = Instant.now(),
    val state: Any  // Engine-specific state (DecompiledApk for jadx)
)

enum class InstanceHealth { HEALTHY, DEGRADED, DEAD }
