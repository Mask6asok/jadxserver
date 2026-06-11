package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.config.TransportMode
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineOptions
import jadx.server.mcp.ToolResult
import jadx.server.server.AcquireResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration tests for class, method, and resource MCP analysis tools
 * against the real APK fixture (com.huawei.hwread.dz.apk, ~77 MB).
 *
 * Each test acquires/fresh-decompiles the APK via the engine pool
 * following the same lifecycle as McpToolsIntegrationTest.
 */
class McpClassResourceTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry
    private lateinit var apkHash: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-class-resource")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state, TransportMode.STDIO)

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

    // ── Helper: run a test block with a decompiled APK ──
    //
    // First call spawns a new engine (NeedSpawn → open → insert).
    // Subsequent calls reuse the idle instance (Found → no-op),
    // so the whole suite avoids re-decompiling the 77 MB APK per test.

    private fun withApk(test: (DecompiledApk) -> Unit) {
        val entry = state.fileIndex.resolve(apkHash)!!
        val engineOptions = EngineOptions(xrefMode = state.config.xrefMode)
        val acquireResult = state.enginePool.acquire("test", apkHash, engineOptions)
        val instance = when (acquireResult) {
            is AcquireResult.Found -> acquireResult.instance
            is AcquireResult.NeedSpawn -> {
                val inst = state.engine.open(Path.of(entry.path), engineOptions)
                state.enginePool.insert("test", apkHash, inst)
                inst
            }
            else -> throw AssertionError("Unexpected acquire result: $acquireResult")
        }
        try {
            val apk = instance.state as DecompiledApk
            test(apk)
        } finally {
            state.enginePool.release(instance)
        }
    }

    // ═══════════════════════════════════════════════
    //  Core tools
    // ═══════════════════════════════════════════════

    @Test
    fun testDecompileApk() = withApk { apk ->
        val result = registry.executeAnalysis("decompile_apk", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("package_name"))
        assertTrue(data.containsKey("class_count"))
        assertTrue(data.containsKey("method_count"))
        assertTrue(data.containsKey("permissions"))

        val pkg = (data["package_name"] as? JsonPrimitive)?.content
        assertNotNull(pkg)
        assertTrue(pkg.isNotEmpty(), "package_name must not be empty")

        val classCount = (data["class_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(classCount)
        assertTrue(classCount > 0, "Expected >0 classes, got $classCount")

        val methodCount = (data["method_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(methodCount)
        assertTrue(methodCount > 0, "Expected >0 methods, got $methodCount")

        val permissions = data["permissions"]
        assertNotNull(permissions)
        assertTrue(permissions is JsonArray)
    }

    @Test
    fun testSurvey() = withApk { apk ->
        val result = registry.executeAnalysis("survey", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("metadata"))
        assertTrue(data.containsKey("top_classes"))
        assertTrue(data.containsKey("permissions"))
        assertTrue(data.containsKey("resource_count"))
        assertTrue(data.containsKey("resource_types"))

        val metadata = data["metadata"] as? JsonObject
        assertNotNull(metadata)
        assertTrue(metadata.containsKey("package_name"))

        val topClasses = data["top_classes"] as? JsonArray
        assertNotNull(topClasses)
        assertTrue(topClasses.size > 0, "Expected at least one top class")
    }

    @Test
    fun testAnalysisStatus() = withApk { apk ->
        val result = registry.executeAnalysis("analysis_status", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("status"))
        assertEquals("analyzed", (data["status"] as? JsonPrimitive)?.content)

        assertTrue(data.containsKey("package_name"))
        val pkg = (data["package_name"] as? JsonPrimitive)?.content
        assertNotNull(pkg)
        assertTrue(pkg.isNotEmpty())

        assertTrue(data.containsKey("class_count"))
        assertTrue(data.containsKey("method_count"))
    }

    // ═══════════════════════════════════════════════
    //  Class tools
    // ═══════════════════════════════════════════════

    @Test
    fun testListClasses() = withApk { apk ->
        val result = registry.executeAnalysis("list_classes", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("count"))
        assertTrue(data.containsKey("classes"))

        val count = (data["count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(count)
        assertTrue(count > 0, "Expected >0 classes, got $count")

        val classes = data["classes"] as? JsonArray
        assertNotNull(classes)
        assertTrue(classes.size > 0)
        // Each entry should have name/package/method_count
        val first = classes[0] as? JsonObject
        assertNotNull(first)
        assertTrue(first.containsKey("name"))
    }

    @Test
    fun testListClassesWithFilter() = withApk { apk ->
        val result = registry.executeAnalysis("list_classes", apk, buildJsonObject {
            put("package", JsonPrimitive("android"))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("count"))
        assertTrue(data.containsKey("classes"))

        val count = (data["count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(count)
        // Filter may return 0 if no classes match the filter prefix; that's valid
        assertTrue(count >= 0, "Count must be >= 0")
    }

    @Test
    fun testGetClassCode() = withApk { apk ->
        val firstClassName = apk.classes.keys.firstOrNull()
        assertNotNull(firstClassName, "No classes found in APK")

        val result = registry.executeAnalysis("get_class_code", apk, buildJsonObject {
            put("class_name", JsonPrimitive(firstClassName))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertEquals(firstClassName, (data["class_name"] as? JsonPrimitive)?.content)

        val source = (data["source"] as? JsonPrimitive)?.content
        assertNotNull(source)
        assertTrue(source.isNotEmpty(), "Source code must not be empty")
    }

    @Test
    fun testGetClassCodeMissingParam() = withApk { apk ->
        val result = registry.executeAnalysis("get_class_code", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
        assertTrue(result.message.contains("class_name", ignoreCase = true))
    }

    @Test
    fun testClassInfo() = withApk { apk ->
        val firstClassName = apk.classes.keys.firstOrNull()
        assertNotNull(firstClassName, "No classes found in APK")

        val result = registry.executeAnalysis("class_info", apk, buildJsonObject {
            put("class_name", JsonPrimitive(firstClassName))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertEquals(firstClassName, (data["name"] as? JsonPrimitive)?.content)
        assertTrue(data.containsKey("superclass"))
        assertTrue(data.containsKey("interfaces"))
        assertTrue(data.containsKey("methods"))
        assertTrue(data.containsKey("fields"))
        assertTrue(data.containsKey("inner_classes"))

        val methods = data["methods"] as? JsonArray
        assertNotNull(methods)
        assertTrue(methods.size > 0, "Expected at least one method in class info")
    }

    @Test
    fun testClassInfoMissingParam() = withApk { apk ->
        val result = registry.executeAnalysis("class_info", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
        assertTrue(result.message.contains("class_name", ignoreCase = true))
    }

    // ═══════════════════════════════════════════════
    //  Method tools
    // ═══════════════════════════════════════════════

    @Test
    fun testListMethods() = withApk { apk ->
        val classWithMethods = apk.classes.values.firstOrNull { it.methods.isNotEmpty() }
        assertNotNull(classWithMethods, "No class with methods found")

        val result = registry.executeAnalysis("list_methods", apk, buildJsonObject {
            put("class_name", JsonPrimitive(classWithMethods.fullName))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("count"))
        assertTrue(data.containsKey("methods"))

        val count = (data["count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(count)
        assertTrue(count > 0, "Expected >0 methods, got $count")
    }

    @Test
    fun testListMethodsMissingParam() = withApk { apk ->
        val result = registry.executeAnalysis("list_methods", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
        assertTrue(result.message.contains("class_name", ignoreCase = true))
    }

    @Test
    fun testGetMethodCode() = withApk { apk ->
        val firstClass = apk.classes.values.firstOrNull { it.methods.isNotEmpty() }
        assertNotNull(firstClass, "No class with methods found")
        val firstMethod = firstClass.methods.first()
        assertNotNull(firstMethod, "No methods in class")

        val result = registry.executeAnalysis("get_method_code", apk, buildJsonObject {
            put("class_name", JsonPrimitive(firstClass.fullName))
            put("method_name", JsonPrimitive(firstMethod.name))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        val source = (data["source"] as? JsonPrimitive)?.content
        assertNotNull(source)
        assertTrue(source.isNotEmpty(), "Method source code must not be empty")
    }

    @Test
    fun testGetMethodCodeMissingParams() = withApk { apk ->
        // Missing class_name
        val result1 = registry.executeAnalysis("get_method_code", apk, buildJsonObject {
            put("method_name", JsonPrimitive("onCreate"))
        })
        assertTrue(result1 is ToolResult.Error)
        assertEquals(-32602, result1.code)
        assertTrue(result1.message.contains("class_name", ignoreCase = true))

        // Missing method_name
        val result2 = registry.executeAnalysis("get_method_code", apk, buildJsonObject {
            put("class_name", JsonPrimitive("some.Class"))
        })
        assertTrue(result2 is ToolResult.Error)
        assertEquals(-32602, result2.code)
        assertTrue(result2.message.contains("method_name", ignoreCase = true))
    }

    @Test
    fun testGetMethodCodeClassNotFound() = withApk { apk ->
        val result = registry.executeAnalysis("get_method_code", apk, buildJsonObject {
            put("class_name", JsonPrimitive("nonexistent.ClassName"))
            put("method_name", JsonPrimitive("foo"))
        })
        assertTrue(result is ToolResult.Error)
        assertEquals(-32001, result.code)
    }

    // ═══════════════════════════════════════════════
    //  Resource tools
    // ═══════════════════════════════════════════════

    @Test
    fun testGetManifest() = withApk { apk ->
        val result = registry.executeAnalysis("get_manifest", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val data = result.data
        assertTrue(data.containsKey("content"))

        val content = (data["content"] as? JsonPrimitive)?.content
        assertNotNull(content)
        assertTrue(
            content.contains("manifest", ignoreCase = true),
            "Manifest content should contain 'manifest'"
        )
    }

    @Test
    fun testListResources() = withApk { apk ->
        val result = registry.executeAnalysis("list_resources", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("count"))
        assertTrue(data.containsKey("resources"))

        val count = (data["count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(count)
        assertTrue(count > 0, "Expected >0 resources, got $count")
    }

    @Test
    fun testListResourcesWithFilter() = withApk { apk ->
        val result = registry.executeAnalysis("list_resources", apk, buildJsonObject {
            put("type", JsonPrimitive("xml"))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertTrue(data.containsKey("count"))
        assertTrue(data.containsKey("resources"))

        val resources = data["resources"] as? JsonArray
        assertNotNull(resources)
        if (resources.size > 0) {
            val first = resources[0] as? JsonObject
            assertNotNull(first)
            assertEquals("xml", (first["type"] as? JsonPrimitive)?.content?.lowercase())
        }
    }

    @Test
    fun testGetResource() = withApk { apk ->
        val resources = apk.listResources()
        assertTrue(resources.isNotEmpty(), "Expected at least one resource")
        val firstResource = resources.first()

        val result = registry.executeAnalysis("get_resource", apk, buildJsonObject {
            put("path", JsonPrimitive(firstResource.path))
        })
        assertTrue(result is ToolResult.Success)
        val data = result.data

        assertEquals(firstResource.path, (data["path"] as? JsonPrimitive)?.content)
        assertTrue(data.containsKey("content"))

        val content = (data["content"] as? JsonPrimitive)?.content
        assertNotNull(content)
        assertTrue(content.isNotEmpty(), "Resource content must not be empty")
    }

    @Test
    fun testGetResourceNotFound() = withApk { apk ->
        val result = registry.executeAnalysis("get_resource", apk, buildJsonObject {
            put("path", JsonPrimitive("res/nonexistent/file.xml"))
        })
        assertTrue(result is ToolResult.Error)
        assertEquals(-32001, result.code)
    }

    @Test
    fun testGetResourceMissingParam() = withApk { apk ->
        val result = registry.executeAnalysis("get_resource", apk, buildJsonObject {})
        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
        assertTrue(result.message.contains("path", ignoreCase = true))
    }
}
