package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.config.XrefMode
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import jadx.server.mcp.ToolResult
import jadx.server.server.AcquireResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration test for search and cross-reference MCP tools against a real APK fixture.
 *
 * Uses the test/apps/com.huawei.notepad.apk fixture — decompilation is slow,
 * hence the @Tag("slow") to allow exclusion in fast test suites.
 *
 * Tests: search_code, search_string, find_class, class_xrefs (TEXT + JADX),
 * method_xrefs (TEXT + JADX).
 * Each search tool tests both matching and non-matching queries.
 * Each xref tool tests both existing and non-existent targets.
 */
@Tag("slow")
class McpSearchXrefTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry
    private lateinit var apkHash: String
    private lateinit var instance: EngineInstance
    private lateinit var apk: DecompiledApk

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-search-xref")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state)

        val apkFile = Path.of(System.getProperty("user.dir"))
            .resolve("test/apps/com.huawei.notepad.apk")
        assertTrue(Files.exists(apkFile), "No test APK found at: $apkFile")

        val entry = state.fileIndex.add(apkFile, tempDir)
        apkHash = entry.hash

        val engineOptions = EngineOptions(xrefMode = XrefMode.JADX)
        val acquireResult = state.enginePool.acquire("session1", apkHash, engineOptions)
        assertTrue(acquireResult is AcquireResult.NeedSpawn)

        val filePath = Path.of(entry.path)
        instance = state.engine.open(filePath, engineOptions)
        state.enginePool.insert("session1", apkHash, instance)
        apk = instance.state as DecompiledApk
    }

    @AfterTest
    fun tearDown() {
        state.enginePool.release(instance)
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    // ── search_code ──────────────────────────────────────────────────────────

    @Test
    fun testSearchCode() {
        val result = registry.executeAnalysis("search_code", apk, buildJsonObject {
            put("pattern", JsonPrimitive("class"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(matchCount)
        assertTrue(matchCount > 0, "Expected matches for 'class', got 0")
        val matches = result.data["matches"] as? JsonArray
        assertNotNull(matches)
        assertTrue(matches.size > 0)
    }

    @Test
    fun testSearchCodeRegexPattern() {
        val result = registry.executeAnalysis("search_code", apk, buildJsonObject {
            put("pattern", JsonPrimitive("public\\s+class"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(matchCount)
        assertTrue(matchCount > 0, "Expected regex matches for 'public\\s+class', got 0")
    }

    @Test
    fun testSearchCodeNoMatch() {
        val result = registry.executeAnalysis("search_code", apk, buildJsonObject {
            put("pattern", JsonPrimitive("XYZZY_NO_SUCH_PATTERN_987654321"))
        })
        assertTrue(result is ToolResult.Success)
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertEquals(0, matchCount)
    }

    // ── search_string ────────────────────────────────────────────────────────

    @Test
    fun testSearchCodeRepairsPatternMisplacedInDescription() {
        val result = registry.executeAnalysis("search_code", apk, buildJsonObject {
            put("description", JsonPrimitive("pattern: class"))
        })

        assertTrue(result is ToolResult.Success, "Expected compatibility repair, got $result")
        assertEquals("class", (result.data["pattern"] as? JsonPrimitive)?.content)
    }

    @Test
    fun testSearchCodeRejectsUnlabeledDescription() {
        val result = registry.executeAnalysis("search_code", apk, buildJsonObject {
            put("description", JsonPrimitive("class"))
        })

        assertTrue(result is ToolResult.Error)
        assertEquals(-32602, result.code)
    }

    @Test
    fun testSearchString() {
        val result = registry.executeAnalysis("search_string", apk, buildJsonObject {
            put("query", JsonPrimitive("http"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(matchCount)
        assertTrue(matchCount > 0, "Expected string matches for 'http', got 0")
    }

    @Test
    fun testSearchStringRegexPattern() {
        val result = registry.executeAnalysis("search_string", apk, buildJsonObject {
            put("query", JsonPrimitive("http[s]?://"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(matchCount)
        assertTrue(matchCount > 0, "Expected regex matches for 'http[s]?://', got 0")
    }

    // ── find_class ───────────────────────────────────────────────────────────

    @Test
    fun testFindClass() {
        val result = registry.executeAnalysis("find_class", apk, buildJsonObject {
            put("filter", JsonPrimitive("Activity"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(matchCount)
        assertTrue(matchCount > 0, "Expected classes matching 'Activity', got 0")
        val classes = result.data["classes"] as? JsonArray
        assertNotNull(classes)
        assertTrue(classes.size > 0)
    }

    @Test
    fun testFindClassNoMatch() {
        val result = registry.executeAnalysis("find_class", apk, buildJsonObject {
            put("filter", JsonPrimitive("NoSuchClassExistsXYZQWERTY"))
        })
        assertTrue(result is ToolResult.Success)
        val matchCount = (result.data["match_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertEquals(0, matchCount)
    }

    // ── class_xrefs ──────────────────────────────────────────────────────────

    @Test
    fun testClassXrefsText() {
        val className = findSampleClass()
        val result = registry.executeAnalysis("class_xrefs", apk, buildJsonObject {
            put("class_name", JsonPrimitive(className))
            put("mode", JsonPrimitive("text"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val refCount = (result.data["ref_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(refCount, "class_xrefs (TEXT) should include ref_count field")
        val xrefs = result.data["xrefs"]
        assertNotNull(xrefs, "class_xrefs (TEXT) should include xrefs array")
    }

    @Test
    fun testClassXrefsJadx() {
        val className = findSampleClass()
        val result = registry.executeAnalysis("class_xrefs", apk, buildJsonObject {
            put("class_name", JsonPrimitive(className))
            put("mode", JsonPrimitive("jadx"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val refCount = (result.data["ref_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertNotNull(refCount, "class_xrefs (JADX) should include ref_count field")
        val xrefs = result.data["xrefs"]
        assertNotNull(xrefs, "class_xrefs (JADX) should include xrefs array")
    }

    // ── method_xrefs ─────────────────────────────────────────────────────────

    @Test
    fun testMethodXrefsText() {
        val (className, methodName) = findSampleMethod()
        val result = registry.executeAnalysis("method_xrefs", apk, buildJsonObject {
            put("class_name", JsonPrimitive(className))
            put("method_name", JsonPrimitive(methodName))
            put("mode", JsonPrimitive("text"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val xrefs = result.data["xrefs"]
        assertNotNull(xrefs, "method_xrefs (TEXT) should include xrefs object")
    }

    @Test
    fun testMethodXrefsJadx() {
        val (className, methodName) = findSampleMethod()
        val result = registry.executeAnalysis("method_xrefs", apk, buildJsonObject {
            put("class_name", JsonPrimitive(className))
            put("method_name", JsonPrimitive(methodName))
            put("mode", JsonPrimitive("jadx"))
        })
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        val xrefs = result.data["xrefs"]
        assertNotNull(xrefs, "method_xrefs (JADX) should include xrefs object")
    }

    // ── xrefs for non-existent targets ───────────────────────────────────────

    @Test
    fun testXrefsNotFound() {
        // class_xrefs for non-existent class
        val classResult = registry.executeAnalysis("class_xrefs", apk, buildJsonObject {
            put("class_name", JsonPrimitive("com.nonexistent.NoSuchClass"))
        })
        assertTrue(classResult is ToolResult.Success)
        val refCount = (classResult.data["ref_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        assertEquals(0, refCount, "xrefs for non-existent class should return ref_count=0")

        // method_xrefs for non-existent class+method
        val methodResult = registry.executeAnalysis("method_xrefs", apk, buildJsonObject {
            put("class_name", JsonPrimitive("com.nonexistent.NoSuchClass"))
            put("method_name", JsonPrimitive("nonExistentMethod"))
        })
        assertTrue(methodResult is ToolResult.Success)
        val xrefs = methodResult.data["xrefs"]
        assertNotNull(xrefs, "method_xrefs for non-existent target should still include xrefs field")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Find a real, non-inner class from the APK to use as xref target.
     * Prefers classes with at least one method for more realistic test coverage.
     */
    private fun findSampleClass(): String {
        val classes = apk.listClasses(null, 0, 200)
        assertTrue(classes.isNotEmpty(), "No classes found in APK")
        val candidate = classes.firstOrNull { !it.isInner && it.methodCount > 0 }
        return candidate?.name ?: classes.first().name
    }

    /**
     * Find a real class+method pair from the APK to use as method_xrefs target.
     */
    private fun findSampleMethod(): Pair<String, String> {
        val classes = apk.listClasses(null, 0, 200)
        for (cls in classes) {
            val methods = apk.listMethods(cls.name)
            if (methods != null && methods.isNotEmpty()) {
                return cls.name to methods.first().name
            }
        }
        error("No classes with methods found in APK")
    }
}
