package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineOptions
import jadx.server.engine.MockEngine
import jadx.server.config.TransportMode
import jadx.server.mcp.ToolResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ToolRegistryTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-registry")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state, TransportMode.STDIO)
    }

    @AfterTest
    fun tearDown() {
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testServerToolExecution() {
        val args = buildJsonObject {}
        val result = registry.executeServer("server_health", args, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("status"))
        assertEquals("healthy", data["status"]?.let { if (it is JsonPrimitive) it.content else null })
    }

    @Test
    fun testServerToolNotFound() {
        val args = buildJsonObject {}
        val result = registry.executeServer("non_existent_tool", args, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32601, result.code)
    }
}
