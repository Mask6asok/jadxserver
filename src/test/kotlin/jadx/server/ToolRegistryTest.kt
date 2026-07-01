package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineOptions
import jadx.server.engine.MockEngine
import jadx.server.mcp.ToolResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import jadx.server.tools.ToolRegistry.Companion.ToolWeight
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
        registry = ToolRegistry.build(state)
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

    @Test
    fun testAnalysisToolWeightResolution() {
        assertEquals(ToolWeight.HEAVY, registry.analysisToolWeight("decompile_apk"), "heavy tool")
        assertEquals(ToolWeight.HEAVY, registry.analysisToolWeight("search_resource"), "resource search tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("list_classes"), "light tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("get_smali"), "smali tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("get_manifest_summary"), "manifest summary tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("list_manifest_permissions"), "manifest permissions tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("list_manifest_components"), "manifest components tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("search_manifest_components"), "manifest component search tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("list_manifest_intent_filters"), "manifest intent filters tool")
        assertEquals(ToolWeight.LIGHT, registry.analysisToolWeight("get_manifest_entrypoints"), "manifest entrypoints tool")
        assertNull(registry.analysisToolWeight("non_existent_tool"), "unknown tool")
    }

    @Test
    fun testToolCatalogIncludesStructuredManifestTools() {
        val result = registry.executeServer("tool_catalog", buildJsonObject {
            put("query", JsonPrimitive("manifest"))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val tools = result.data["tools"] as? kotlinx.serialization.json.JsonArray
        assertNotNull(tools)
        val names = tools.mapNotNull {
            ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content
        }.toSet()

        assertTrue("get_manifest" in names)
        assertTrue("get_manifest_summary" in names)
        assertTrue("list_manifest_permissions" in names)
        assertTrue("list_manifest_components" in names)
        assertTrue("search_manifest_components" in names)
        assertTrue("list_manifest_intent_filters" in names)
        assertTrue("get_manifest_entrypoints" in names)
    }
}
