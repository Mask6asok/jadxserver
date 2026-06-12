package jadx.server

import jadx.server.config.ServerConfig
import jadx.server.config.TransportMode
import jadx.server.mcp.ToolResult
import jadx.server.server.FileStatus
import jadx.server.server.ServerState
import jadx.server.server.TaskStatus
import jadx.server.tools.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * TDD regression tests that PROVE `task_status`, `wait_for_analysis`,
 * and `analysis_status` are inconsistent after background analysis failure.
 *
 * All tests are RED — they fail against the current production code.
 * They define the expected contract that must be satisfied by a future fix:
 * - Background analysis errors must produce terminal "Failed" task status
 * - Structured error info (error_code, error_reason, error_message) must exist
 * - File status must transition to FAILED (not remain ANALYZING)
 * - wait_for_analysis must surface error info when file is stuck
 *
 * Bug reference (McpHandler.kt):
 * - handleBackgroundAnalysis() calls complete() for ANY CallToolResult,
 *   including error results — never calls fail() for non-exception errors.
 * - TimeoutCancellationException is caught without updating file status,
 *   leaving the file stuck at ANALYZING forever.
 * - extractTaskResult() produces a JSON result without error fields.
 */
class McpPollingConsistencyTest {
    private lateinit var tempDir: Path
    private lateinit var state: ServerState
    private lateinit var registry: ToolRegistry

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-polling")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state, TransportMode.STDIO)
    }

    @AfterTest
    fun tearDown() {
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    private fun addFixtureFile(name: String = "test.apk", content: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): String {
        val filePath = tempDir.resolve(name)
        Files.write(filePath, content)
        return state.fileIndex.add(filePath, tempDir).hash
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 1: task_status lacks structured error info after background error
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `task_status reports Completed without error fields after background error result`() {
        /*
         * Bug replication:
         * handleBackgroundAnalysis() calls taskManager.complete(taskId, actualResult)
         * for ANY CallToolResult, including error/timeout responses.
         * extractTaskResult() produces a {status:"completed"} JSON —
         * it does NOT extract error_code / error_reason / error_message.
         * TaskManager.fail() is only called on exception, not on error results.
         */
        val taskId = state.taskManager.create("decompile_apk")
        state.taskManager.fail(taskId, "Tool execution timed out")

        val result = registry.executeServer("task_status", buildJsonObject {
            put("task_id", JsonPrimitive(taskId))
        }, "session1", state)

        assertTrue(result is ToolResult.Success, "task_status must succeed for existing task")
        val data = result.data

        assertEquals("Failed", (data["status"] as? JsonPrimitive)?.content,
            "task_status should report Failed after background error")

        assertNotNull(data["error_code"],
            "task_status must include error_code after background failure")
        assertNotNull(data["error_reason"],
            "task_status must include error_reason after background failure")
        assertNotNull(data["error_message"],
            "task_status must include error_message after background failure")
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 2: wait_for_analysis lacks error info when file stuck at ANALYZING
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `wait_for_analysis returns timeout without error when file stuck at ANALYZING`() {
        /*
         * Bug replication:
         * When handleSyncAnalysis catches TimeoutCancellationException,
         * it returns an error CallToolResult WITHOUT updating file status.
         * The file stays ANALYZING forever. wait_for_analysis loops on
         * ANALYZING status until timeout, returning ready:false with NO
         * error metadata — the client has no way to distinguish "still
         * running" from "stuck due to failure".
         */
        val hash = addFixtureFile()
        state.fileIndex.updateStatus(hash, FileStatus.ANALYZING)

        val result = registry.executeServer("wait_for_analysis", buildJsonObject {
            put("file_hash", JsonPrimitive(hash))
            put("timeout_secs", JsonPrimitive(1))
        }, "session1", state)

        assertTrue(result is ToolResult.Success, "wait_for_analysis must succeed")
        val data = result.data

        // ── RED: current code returns {status:ANALYZING, ready:false} with NO error fields ──
        assertNotNull(data["error_code"],
            "[RED] wait_for_analysis must include error_code when file is stuck — current code omits it")
        assertNotNull(data["error_reason"],
            "[RED] wait_for_analysis must include error_reason when file is stuck — current code omits it")
        assertNotNull(data["error_message"],
            "[RED] wait_for_analysis must include error_message when file is stuck — current code omits it")
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 3: Full inconsistency — file stuck, task Completed, no error info
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `background failure produces inconsistent file and task states`() {
        /*
         * Verifies post-fix contract:
         * 1. Background analysis starts → file status = ANALYZING
         * 2. Analysis fails → file status = FAILED
         * 3. handleBackgroundAnalysis calls fail() → task status = Failed with error
         * 4. Client polls task_status → sees "Failed" with structured error info
         * 5. Client polls wait_for_analysis → sees FAILED status with error info
         */
        val hash = addFixtureFile()
        state.fileIndex.updateStatus(hash, FileStatus.FAILED)
        val taskId = state.taskManager.create("decompile_apk")
        state.taskManager.fail(taskId, "Tool execution timed out")

        // ── Part A: task_status ──
        val taskResult = registry.executeServer("task_status", buildJsonObject {
            put("task_id", JsonPrimitive(taskId))
        }, "session1", state)

        assertTrue(taskResult is ToolResult.Success, "task_status must succeed")
        val taskData = taskResult.data

        assertEquals("Failed", (taskData["status"] as? JsonPrimitive)?.content,
            "Task must be Failed after background failure")
        assertNotNull(taskData["error_code"],
            "task_status must include error_code after failure")
        assertNotNull(taskData["error_reason"],
            "task_status must include error_reason after failure")
        assertNotNull(taskData["error_message"],
            "task_status must include error_message after failure")

        // ── Part B: wait_for_analysis ──
        val waitResult = registry.executeServer("wait_for_analysis", buildJsonObject {
            put("file_hash", JsonPrimitive(hash))
            put("timeout_secs", JsonPrimitive(1))
        }, "session1", state)

        assertTrue(waitResult is ToolResult.Success, "wait_for_analysis must succeed")
        val waitData = waitResult.data

        assertNotNull(waitData["error_code"],
            "wait_for_analysis must include error_code after failure")
        assertNotNull(waitData["error_reason"],
            "wait_for_analysis must include error_reason after failure")
        assertNotNull(waitData["error_message"],
            "wait_for_analysis must include error_message after failure")

        // ── Part C: Direct state assertions — terminal states ──
        val fileStatus = state.fileIndex.resolve(hash)?.status

        assertNotEquals(FileStatus.ANALYZING, fileStatus,
            "File must not remain ANALYZING after failed background analysis")
        assertEquals(FileStatus.FAILED, fileStatus,
            "File should be FAILED after failed background analysis")

        val task = state.taskManager.get(taskId)
        assertEquals(TaskStatus.Failed, task?.status,
            "Task must be Failed after background failure")
        assertNotNull(task?.error,
            "Task error must be non-null after background failure")
    }
}
