package jadx.server

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
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
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for ALL 10 server tools defined in ServerTools.kt.
 *
 * Uses the same fixture + setUp/tearDown pattern as McpToolsIntegrationTest:
 * - Temp directory per test
 * - ServerState + ToolRegistry built in STDIO mode
 * - Single deterministic APK fixture pre-registered in FileIndex
 */
class McpServerToolsTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry
    private lateinit var apkHash: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-server-tools")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state, TransportMode.STDIO)

        // Single deterministic fixture
        val apkFile = Path.of(System.getProperty("user.dir"))
            .resolve("test/apps/com.huawei.hwread.dz.apk")
        assertTrue(Files.exists(apkFile), "No test APK found at: $apkFile")

        val entry = state.fileIndex.add(apkFile, tempDir)
        apkHash = entry.hash
    }

    @AfterTest
    fun tearDown() {
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testUploadFile() {
        val result = registry.executeServer("upload_file", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        // STDIO mode returns mode + target_dir; HTTP mode returns upload_url
        assertTrue(data.containsKey("mode") || data.containsKey("upload_url"))
        assertTrue(data.containsKey("description"))
    }

    @Test
    fun testSaveProject() {
        val result = registry.executeServer("save_project", buildJsonObject {
            put("file_hash", JsonPrimitive(apkHash))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("file_hash"))
        assertEquals(apkHash, (data["file_hash"] as? JsonPrimitive)?.content)
        assertTrue(data.containsKey("project_file"))
        assertTrue(data.containsKey("cache_dir"))
        assertTrue(data.containsKey("status"))
        assertEquals("saved", (data["status"] as? JsonPrimitive)?.content)
    }

    @Test
    fun testSaveProjectMissingHash() {
        val result = registry.executeServer("save_project", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
    }

    @Test
    fun testSaveProjectNotFound() {
        val result = registry.executeServer("save_project", buildJsonObject {
            put("file_hash", JsonPrimitive("00000000"))
        }, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32001, result.code)
    }

    @Test
    fun testListFiles() {
        val result = registry.executeServer("list_files", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("files"))
        assertTrue(data.containsKey("count"))

        val filesElement = data["files"]
        assertNotNull(filesElement)
        assertTrue(filesElement is JsonArray, "Expected files to be a JsonArray")
        assertTrue(filesElement.size > 0, "Expected at least one file in the index")
    }

    @Test
    fun testListFilesWithFilter() {
        val result = registry.executeServer("list_files", buildJsonObject {
            put("name", JsonPrimitive("hwread"))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        val filesElement = data["files"] as? JsonArray
        assertNotNull(filesElement)
        assertTrue(filesElement.size > 0, "Expected filter to match the fixture file")
    }

    @Test
    fun testListInstances() {
        val result = registry.executeServer("list_instances", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("instances"))
        assertTrue(data.containsKey("count"))

        val instances = data["instances"]
        assertNotNull(instances)
        assertTrue(instances is JsonArray)
    }

    @Test
    fun testListInstancesAfterDecompile() {
        // Decompile the APK to create an engine instance
        val entry = state.fileIndex.resolve(apkHash)!!
        val engineOptions = EngineOptions(xrefMode = state.config.xrefMode)
        val acquireResult = state.enginePool.acquire("session1", apkHash, engineOptions)
        assertTrue(acquireResult is AcquireResult.NeedSpawn)
        val filePath = Path.of(entry.path)
        val instance = state.engine.open(filePath, engineOptions)
        state.enginePool.insert("session1", apkHash, instance)
        try {
            val apk = instance.state as DecompiledApk
            registry.executeAnalysis("decompile_apk", apk, buildJsonObject {})
        } finally {
            state.enginePool.release(instance)
        }

        // Now list_instances should show 1 instance
        val result = registry.executeServer("list_instances", buildJsonObject {}, "session1", state)
        assertTrue(result is ToolResult.Success)
        val data = result.data
        val instances = data["instances"] as? JsonArray
        assertNotNull(instances)
        assertTrue(instances.size > 0, "Expected at least one instance after decompile")
    }

    @Test
    fun testServerHealth() {
        val result = registry.executeServer("server_health", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("status"))
        assertEquals("healthy", (data["status"] as? JsonPrimitive)?.content)
        assertTrue(data.containsKey("uptime_seconds"))
        assertTrue(data.containsKey("instance_count"))
        assertTrue(data.containsKey("session_count"))
        assertTrue(data.containsKey("file_count"))
        assertTrue(data.containsKey("task_count"))
        assertTrue(data.containsKey("memory"))
    }

    @Test
    fun testToolCatalog() {
        val result = registry.executeServer("tool_catalog", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("tools"))
        assertTrue(data.containsKey("count"))

        val tools = data["tools"]
        assertNotNull(tools)
        assertTrue(tools is JsonArray)
        assertTrue(tools.size > 0, "Expected at least one tool in the catalog")
    }

    @Test
    fun testToolCatalogWithQuery() {
        val result = registry.executeServer("tool_catalog", buildJsonObject {
            put("query", JsonPrimitive("decompile"))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        val tools = data["tools"] as? JsonArray
        assertNotNull(tools)
        assertTrue(tools.size > 0, "Expected at least one matching tool for 'decompile'")
    }

    @Test
    fun testToolHelp() {
        val result = registry.executeServer("tool_help", buildJsonObject {
            put("tool", JsonPrimitive("decompile_apk"))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("name"))
        assertEquals("decompile_apk", (data["name"] as? JsonPrimitive)?.content)
        assertTrue(data.containsKey("description"))
        assertTrue(data.containsKey("parameters"))
    }

    @Test
    fun testToolHelpMissingParam() {
        val result = registry.executeServer("tool_help", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
    }

    @Test
    fun testToolHelpNotFound() {
        val result = registry.executeServer("tool_help", buildJsonObject {
            put("tool", JsonPrimitive("non_existent_tool"))
        }, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32001, result.code)
    }

    @Test
    fun testTaskStatus() {
        val result = registry.executeServer("task_status", buildJsonObject {
            put("task_id", JsonPrimitive("non_existent_task"))
        }, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32001, result.code)
    }

    @Test
    fun testTaskStatusMissingParam() {
        val result = registry.executeServer("task_status", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
    }

    @Test
    fun testWaitForAnalysis() {
        // First decompile the APK so its status becomes ANALYZED
        val entry = state.fileIndex.resolve(apkHash)!!
        val engineOptions = EngineOptions(xrefMode = state.config.xrefMode)

        val acquireResult = state.enginePool.acquire("session1", apkHash, engineOptions)
        assertTrue(acquireResult is AcquireResult.NeedSpawn)

        val filePath = Path.of(entry.path)
        val instance = state.engine.open(filePath, engineOptions)
        state.enginePool.insert("session1", apkHash, instance)

        try {
            val apk = instance.state as DecompiledApk
            registry.executeAnalysis("decompile_apk", apk, buildJsonObject {})
        } finally {
            state.enginePool.release(instance)
        }

        // Now wait_for_analysis should report ready immediately
        val result = registry.executeServer("wait_for_analysis", buildJsonObject {
            put("file_hash", JsonPrimitive(apkHash))
            put("timeout_secs", JsonPrimitive(5))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("file_hash"))
        assertEquals(apkHash, (data["file_hash"] as? JsonPrimitive)?.content)
        assertTrue(data.containsKey("ready"))
        assertTrue(data.containsKey("status"))
    }

    @Test
    fun testWaitForAnalysisMissingHash() {
        val result = registry.executeServer("wait_for_analysis", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
    }

    @Test
    fun testWaitForAnalysisNotFound() {
        val result = registry.executeServer("wait_for_analysis", buildJsonObject {
            put("file_hash", JsonPrimitive("00000000"))
        }, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32001, result.code)
    }

    @Test
    fun testCleanupSessionWorkers() {
        val result = registry.executeServer("cleanup_session_workers", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("closed_count"))
        assertTrue(data.containsKey("remaining"))
    }

    @Test
    fun testCleanupSessionWorkersWithForce() {
        val result = registry.executeServer("cleanup_session_workers", buildJsonObject {
            put("force", JsonPrimitive(true))
        }, "session1", state)

        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("closed_count"))
        assertTrue(data.containsKey("remaining"))
    }

    @Test
    fun testUnknownTool() {
        val result = registry.executeServer("non_existent_tool", buildJsonObject {}, "session1", state)

        assertTrue(result is ToolResult.Error)
        assertEquals(-32601, result.code)
    }
}

class HttpAuthorizationTest {
    @Test
    fun `missing configured token leaves HTTP endpoints unauthenticated`() = testApplication {
        application {
            installOptionalBearerAuthorization(null)
            testRoutes()
        }

        assertEquals(HttpStatusCode.OK, client.post("/mcp").status)
        assertEquals(HttpStatusCode.OK, client.post("/upload").status)
    }

    @Test
    fun `configured token protects MCP and upload endpoints`() = testApplication {
        application {
            installOptionalBearerAuthorization("server-secret")
            testRoutes()
        }

        for (path in listOf("/mcp", "/upload")) {
            val missing = client.post(path)
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertEquals("Bearer realm=\"jadx-server\"", missing.headers[HttpHeaders.WWWAuthenticate])

            val wrong = client.post(path) {
                header(HttpHeaders.Authorization, "Bearer wrong-secret")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)

            val valid = client.post(path) {
                header(HttpHeaders.Authorization, "Bearer server-secret")
            }
            assertEquals(HttpStatusCode.OK, valid.status)
        }
    }

    @Test
    fun `non-bearer authorization is rejected`() = testApplication {
        application {
            installOptionalBearerAuthorization("server-secret")
            testRoutes()
        }

        val response = client.post("/mcp") {
            header(HttpHeaders.Authorization, "Basic server-secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `CORS preflight allows authorization header without authentication`() = testApplication {
        application {
            installHttpCors()
            installOptionalBearerAuthorization("server-secret")
            testRoutes()
        }

        val response = client.options("/mcp") {
            header(HttpHeaders.Origin, "https://client.example")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        val allowedHeaders = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
        assertTrue(allowedHeaders.contains("authorization"))
    }

    private fun io.ktor.server.application.Application.testRoutes() {
        routing {
            post("/mcp") { call.respondText("ok") }
            post("/upload") { call.respondText("ok") }
        }
    }
}
