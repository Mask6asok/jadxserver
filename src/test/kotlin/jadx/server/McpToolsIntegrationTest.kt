package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.config.TransportMode
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineOptions
import jadx.server.mcp.ToolResult
import jadx.server.server.AcquireResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration test for MCP tools against a real APK fixture.
 *
 * Uses a single deterministic fixture: test/apps/com.huawei.hwread.dz.apk.
 * FileIndex.add() uses copy semantics (Task 1 fix), so the source stays
 * in test/apps/ and the test temp dir gets a working copy.
 */
class McpToolsIntegrationTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry
    private lateinit var apkHash: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-mcp")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state, TransportMode.STDIO)

        // Single deterministic fixture — no fallback candidates
        val apkFile = Path.of(System.getProperty("user.dir"))
            .resolve("test/apps/com.huawei.hwread.dz.apk")
        assertTrue(Files.exists(apkFile), "No test APK found at: $apkFile")

        // FileIndex.add(Path, moveToDir) copies the file to tempDir/binary/<md5>/<name>
        // so the original fixture in test/apps/ is never modified.
        val entry = state.fileIndex.add(apkFile, tempDir)
        apkHash = entry.hash
    }

    @AfterTest
    fun tearDown() {
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testServerHealth() {
        val result = registry.executeServer("server_health", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("status"))
        assertEquals("healthy", (data["status"] as? JsonPrimitive)?.content)
    }

    @Test
    fun testFileRegistered() {
        val entry = assertNotNull(state.fileIndex.resolve(apkHash))
        assertEquals("com.huawei.hwread.dz.apk", entry.originalName)
    }

    @Test
    fun testToolNotFound() {
        val result = registry.executeServer("non_existent_tool", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32601, result.code)
    }

    @Test
    fun testDecompileApk() {
        val entry = state.fileIndex.resolve(apkHash)!!
        val engineOptions = EngineOptions(xrefMode = state.config.xrefMode)

        val acquireResult = state.enginePool.acquire("session1", apkHash, engineOptions)
        assertTrue(acquireResult is AcquireResult.NeedSpawn)

        val filePath = Path.of(entry.path)
        val instance = state.engine.open(filePath, engineOptions)
        state.enginePool.insert("session1", apkHash, instance)

        try {
            val apk = instance.state as DecompiledApk
            val result = registry.executeAnalysis("decompile_apk", apk, buildJsonObject {})

            assertTrue(result is ToolResult.Success)
            val data = result.data

            assertTrue(data.containsKey("package_name"))
            assertTrue(data.containsKey("class_count"))
            assertTrue(data.containsKey("method_count"))

            // Verify the APK was actually decompiled with meaningful results
            val classCount = (data["class_count"] as? JsonPrimitive)?.content?.toIntOrNull()
            assertNotNull(classCount)
            assertTrue(classCount > 0, "Expected at least 1 class, got $classCount")
        } finally {
            state.enginePool.release(instance)
        }
    }

    @Test
    fun testListFiles() {
        val result = registry.executeServer("list_files", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("files"))

        val filesElement = data["files"]
        assertNotNull(filesElement)
        assertTrue(filesElement is JsonArray, "Expected files to be a JsonArray")
        assertTrue(filesElement.size > 0, "Expected at least one file in the index")
    }
}
