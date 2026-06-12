package jadx.server

import jadx.server.fixture.ServerStateTestBase
import jadx.server.mcp.ToolResult
import jadx.server.server.FileStatus
import jadx.server.server.TaskStatus
import jadx.server.tools.CoreTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class McpConcurrencyTest : ServerStateTestBase() {

    @Test
    fun `background completion and simultaneous polling keep task and file terminal states consistent`() {
        runTerminalStateRace(
            terminalFileStatus = FileStatus.ANALYZED,
            completeTask = { taskId ->
                state.taskManager.complete(taskId, buildJsonObject {
                    put("status", JsonPrimitive("completed"))
                })
            },
            assertWaitResult = { waitData ->
                assertEquals("ANALYZED", waitData["status"]?.jsonPrimitive?.content)
                assertEquals("true", waitData["ready"]?.jsonPrimitive?.content)
            },
            expectedTaskStatus = TaskStatus.Completed
        )
    }

    @Test
    fun `background failure and simultaneous polling never leave file analyzing after terminal task`() {
        runTerminalStateRace(
            terminalFileStatus = FileStatus.FAILED,
            completeTask = { taskId ->
                state.taskManager.fail(taskId, "simulated failure")
            },
            assertWaitResult = { waitData ->
                assertEquals("FAILED", waitData["status"]?.jsonPrimitive?.content)
                assertEquals("false", waitData["ready"]?.jsonPrimitive?.content)
                assertEquals("FAILED", waitData["error_code"]?.jsonPrimitive?.content)
            },
            expectedTaskStatus = TaskStatus.Failed
        )
    }

    private fun runTerminalStateRace(
        terminalFileStatus: FileStatus,
        completeTask: (String) -> Unit,
        assertWaitResult: (JsonObject) -> Unit,
        expectedTaskStatus: TaskStatus
    ) {
        val hash = addFixtureFile()
        state.fileIndex.updateStatus(hash, FileStatus.ANALYZING)
        val taskId = state.taskManager.create("decompile_apk")

        val startBarrier = CyclicBarrier(2)
        val fileTerminal = CountDownLatch(1)
        val allowTaskTerminal = CountDownLatch(1)
        val terminalObserved = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val backgroundThread = Thread.ofVirtual().name("test-background-$taskId").start {
            try {
                startBarrier.await(2, TimeUnit.SECONDS)
                state.fileIndex.updateStatus(hash, terminalFileStatus)
                fileTerminal.countDown()
                assertTrue(allowTaskTerminal.await(2, TimeUnit.SECONDS), "poller did not begin terminal-state check")
                completeTask(taskId)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val pollingThread = Thread.ofVirtual().name("test-poller-$taskId").start {
            try {
                startBarrier.await(2, TimeUnit.SECONDS)
                assertTrue(fileTerminal.await(2, TimeUnit.SECONDS), "background thread did not publish terminal file state")
                allowTaskTerminal.countDown()

                while (state.taskManager.get(taskId)?.status == TaskStatus.Running) {
                    val taskData = successData(registry.executeServer("task_status", buildJsonObject {
                        put("task_id", JsonPrimitive(taskId))
                    }, "session1", state))
                    val polledStatus = taskData["status"]?.jsonPrimitive?.content
                    assertTrue(polledStatus == "Running" || polledStatus == expectedTaskStatus.name,
                        "task_status must never report an unexpected state during terminal handoff")
                }

                val taskData = successData(registry.executeServer("task_status", buildJsonObject {
                    put("task_id", JsonPrimitive(taskId))
                }, "session1", state))
                val fileData = successData(CoreTools.analysisStatus(state, hash))
                val waitData = successData(registry.executeServer("wait_for_analysis", buildJsonObject {
                    put("file_hash", JsonPrimitive(hash))
                    put("timeout_secs", JsonPrimitive(1))
                }, "session1", state))

                assertEquals(expectedTaskStatus.name, taskData["status"]?.jsonPrimitive?.content)
                assertNotEquals("analyzing", fileData["status"]?.jsonPrimitive?.content)
                assertNotEquals(FileStatus.ANALYZING, state.fileIndex.resolve(hash)?.status)
                assertEquals(terminalFileStatus.name.lowercase(), fileData["status"]?.jsonPrimitive?.content)
                assertWaitResult(waitData)

                if (expectedTaskStatus == TaskStatus.Failed) {
                    assertNotNull(taskData["error_code"])
                    assertFalse(taskData["error_message"]?.jsonPrimitive?.content.isNullOrBlank())
                }
                terminalObserved.countDown()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        backgroundThread.join(2_000)
        pollingThread.join(2_000)

        failure.get()?.let { throw it }
        assertTrue(terminalObserved.await(1, TimeUnit.SECONDS), "poller did not observe terminal state")
        assertEquals(expectedTaskStatus, state.taskManager.get(taskId)?.status)
        assertNotEquals(FileStatus.ANALYZING, state.fileIndex.resolve(hash)?.status)
        if (backgroundThread.isAlive || pollingThread.isAlive) {
            fail("concurrency test threads did not finish")
        }
    }

    private fun successData(result: ToolResult): JsonObject {
        assertTrue(result is ToolResult.Success, "Expected success, got $result")
        return result.data
    }
}
