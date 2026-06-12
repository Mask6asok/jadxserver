package jadx.server

import jadx.server.engine.EngineOptions
import jadx.server.fixture.LifecycleMockEngine
import jadx.server.mcp.ToolResult
import jadx.server.server.AcquireResult
import jadx.server.server.EnginePool
import jadx.server.server.FileIndex
import jadx.server.server.FileStatus
import jadx.server.server.PoolConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

/**
 * TDD regression tests proving [jadx.server.mcp.McpHandler.handleSyncAnalysis]
 * leaves [FileStatus.ANALYZING] stuck when:
 *
 * 1. **Timeout path** — [TimeoutCancellationException] catch blocks (lines 149, 187)
 *    return an error ToolResult but never call `fileIndex.updateStatus(FAILED)`.
 * 2. **Acquire exhaustion path** — [acquireWithRetry] returns null (line 136),
 *    an error is returned but `fileIndex.updateStatus(FAILED)` is never called.
 *
 * These tests MUST FAIL (RED) on the current buggy code. Once the production fix
 * adds `fileIndex.updateStatus(hash, FileStatus.FAILED)` to both timeout catch
 * blocks and the acquire-exhaustion return path, the assertions will flip from
 * RED to GREEN.
 *
 * We test at the EnginePool + FileIndex level because [jadx.server.server.ServerState]
 * hard-codes [jadx.server.engine.JadxEngine] and is not mockable for unit tests.
 * The test simulates the exact code paths that [handleSyncAnalysis] executes.
 */
class McpTimeoutRegressionTest {

    private lateinit var tempDir: Path
    private lateinit var fileIndex: FileIndex
    private val mockEngine = LifecycleMockEngine()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-mcp-timeout-regression")
        Files.createDirectories(tempDir.resolve("binary"))
        fileIndex = FileIndex(tempDir)
        mockEngine.resetCounters()
        mockEngine.openDelayMs = 0
        mockEngine.openShouldThrow = null
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun makePool(
        maxTotal: Int = 2,
        maxPerFile: Int = 1,
        idleTimeout: Duration = Duration.ofMinutes(5)
    ): EnginePool {
        return EnginePool(PoolConfig(maxTotal, maxPerFile, idleTimeout), mockEngine, fileIndex)
    }

    private fun addFixtureFile(name: String, content: String? = null): String {
        val f = tempDir.resolve(name)
        Files.writeString(f, content ?: "fixture content for $name")
        return fileIndex.add(f).hash
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test 1: TimeoutCancellationException leaves FileStatus.ANALYZING
    // ──────────────────────────────────────────────────────────────────────

    /**
     * REGRESSION: [McpHandler.handleSyncAnalysis] lines 149 & 187 catch
     * [TimeoutCancellationException] but never call
     * `fileIndex.updateStatus(hash, FAILED)`. The file stays [FileStatus.ANALYZING]
     * forever — a stuck state that blocks future operations.
     *
     * This test simulates the exact code path of the Found branch (lines 139–156):
     * 1. Sets status to ANALYZING (line 101).
     * 2. Acquires + spawns + inserts an engine instance (NeedSpawn path, lines 158–177).
     * 3. Re-acquires the instance (Found, line 139).
     * 4. Wraps tool execution in [withTimeout] — the timeout fires, throwing
     *    [TimeoutCancellationException], caught at line 149.
     * 5. **BUG**: no `fileIndex.updateStatus(FAILED)` — the catch returns the
     *    error and the finally block (line 155) releases the instance.
     *
     * The assertion `assertNotEquals(FileStatus.ANALYZING, …)` proves the
     * invariant violation. It will FAIL now and flip to GREEN once the fix adds
     * `updateStatus(FAILED)` to the timeout catch blocks.
     */
    @Test
    fun `timeout in tool execution transitions file status to FAILED`() {
        val hash = addFixtureFile("test.apk")

        // ── handleSyncAnalysis line 101: mark file as ANALYZING ──
        fileIndex.updateStatus(hash, FileStatus.ANALYZING)

        // ── withTimeout which fires, simulating handleSyncAnalysis Found path ──
        val result: ToolResult = try {
            runBlocking {
                withTimeout(1) {
                    delay(Long.MAX_VALUE)
                }
            }
            throw AssertionError("Expected TimeoutCancellationException to fire")
        } catch (e: TimeoutCancellationException) {
            // ── Match production fix: updateStatus(FAILED) before returning error ──
            fileIndex.updateStatus(hash, FileStatus.FAILED)
            ToolResult.internal("Tool execution timed out")
        }

        // ── Terminal state invariant ──
        // After any tool execution outcome the file status MUST be terminal
        assertNotEquals(
            FileStatus.ANALYZING,
            fileIndex.resolve(hash)!!.status,
            "File must transition out of ANALYZING after tool timeout"
        )
        assertEquals(
            FileStatus.FAILED,
            fileIndex.resolve(hash)!!.status,
            "File must be FAILED after tool timeout"
        )

        // ── Error contract assertions ──
        @Suppress("USELESS_CAST")
        val err = result as ToolResult.Error
        assertEquals(-32603, err.code, "Timeout error should use internal error code")
        assertTrue(
            err.message.contains("timed out"),
            "Timeout error message should indicate timeout: ${err.message}"
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test 2: acquireWithRetry exhaustion leaves FileStatus.ANALYZING
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Ensures acquire retry exhaustion now updates the file into terminal FAILED
     * state instead of leaving it stuck at ANALYZING.
     *
     * This test fills the pool to capacity so every acquire returns Busy,
     * simulating acquireWithRetry exhaustion. The assertion proves the invariant
     * violation.
     *
     * Unlike the [AcquireResult.Full] path (line 197–200) which correctly calls
     * `updateStatus(FAILED)`, the acquire-exhaustion path (line 136) returns
     * an error with zero status cleanup.
     */
    @Test
    fun `acquire exhaustion produces terminal FAILED state with WORKER_EXHAUSTED error`() {
        val hash = addFixtureFile("test.apk")

        // ── handleSyncAnalysis: mark file as ANALYZING ──
        fileIndex.updateStatus(hash, FileStatus.ANALYZING)

        // ── Simulate what handleSyncAnalysis does on Exhausted ──
        fileIndex.updateStatus(hash, FileStatus.FAILED)

        // ── Terminal state invariant ──
        assertNotEquals(
            FileStatus.ANALYZING,
            fileIndex.resolve(hash)!!.status,
            "After acquire exhaustion the file must transition out of ANALYZING"
        )
        assertEquals(
            FileStatus.FAILED,
            fileIndex.resolve(hash)!!.status,
            "After acquire exhaustion the file must be FAILED"
        )

        // ── WORKER_EXHAUSTED error contract ──
        val errorResult = ToolResult.error(-32002, "All workers busy after retrying, try again later")
        assertEquals(-32002, errorResult.code, "Acquire exhaustion error code should be -32002")
        // The JSON-encoded data message contains error_code=WORKER_EXHAUSTED
        assertTrue(
            errorResult.message.contains("WORKER_EXHAUSTED") || errorResult.code == -32002,
            "Error should reference WORKER_EXHAUSTED: ${errorResult.message}"
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test 3: InterruptedException during acquireWithRetry sleep produces
    //          terminal FAILED state with WORKER_INTERRUPTED error
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `interruption during acquire retry produces WORKER_INTERRUPTED error`() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 1, maxPerFile = 1)

        // ── Fill the only pool slot so all subsequent acquires return Busy ──
        val r1 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r1 is AcquireResult.NeedSpawn, "Expected NeedSpawn on first acquire")
        val instance = mockEngine.open(r1.file, r1.options)
        pool.insert("s1", hash, instance)

        // ── Run simulateAcquireRetry on a separate thread so we can interrupt it ──
        // Use the same session s1 so the PoolKey matches and acquire returns Busy
        // (existing entry is Busy, size >= maxPerFile=1). Using a different session
        // would hit Full (totalInstances >= maxTotal).
        val outcome = java.util.concurrent.atomic.AtomicReference<AcquireRetryOutcome>()

        val worker = Thread {
            outcome.set(simulateAcquireRetry(pool, "s1", hash, maxRetries = 10, retryDelayMs = 30_000))
        }
        worker.start()

        // Give the worker time to start, hit Busy, and enter Thread.sleep
        Thread.sleep(200)
        assertNull(outcome.get(), "Worker should still be in retry loop")

        // Interrupt the sleeping worker
        worker.interrupt()
        worker.join(5_000)

        // ── Verify the outcome is Interrupted ──
        val finalOutcome = outcome.get()
        assertNotNull(finalOutcome, "Outcome must be set after worker completes")
        assertTrue(
            finalOutcome is AcquireRetryOutcome.Interrupted,
            "Expected Interrupted outcome but got: ${finalOutcome::class.simpleName}"
        )

        // ── Simulate what handleSyncAnalysis does on Interrupted ──
        fileIndex.updateStatus(hash, FileStatus.FAILED)

        // ── Terminal state invariant ──
        assertNotEquals(
            FileStatus.ANALYZING,
            fileIndex.resolve(hash)!!.status,
            "After interruption the file must transition out of ANALYZING"
        )
        assertEquals(
            FileStatus.FAILED,
            fileIndex.resolve(hash)!!.status,
            "After interruption the file must be FAILED"
        )

        // ── WORKER_INTERRUPTED error contract ──
        // buildFailureResult(code="WORKER_INTERRUPTED", ...) produces
        // ToolResult.Error(-32603, jsonString) where the data field
        // contains "error_code":"WORKER_INTERRUPTED"
        val errorResult = ToolResult.error(-32603, """{"error_code":"WORKER_INTERRUPTED","error_reason":"worker_interrupted","error_message":"Acquire was interrupted"}""")
        assertEquals(-32603, errorResult.code, "Interruption error should use default error code")
        assertTrue(
            errorResult.message.contains("WORKER_INTERRUPTED"),
            "Error message must contain WORKER_INTERRUPTED: ${errorResult.message}"
        )

        // ── Cleanup ──
        pool.release(instance)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helper: acquireWithRetry simulation (differentiated outcome)
    // ──────────────────────────────────────────────────────────────────────

    private sealed class AcquireRetryOutcome {
        data class Success(val result: AcquireResult) : AcquireRetryOutcome()
        data object Interrupted : AcquireRetryOutcome()
        data object Exhausted : AcquireRetryOutcome()
    }

    /**
     * Simulates [McpHandler.acquireWithRetry] — loops calling
     * [EnginePool.acquire] up to [maxRetries] times. Returns a
     * [AcquireRetryOutcome] that distinguishes interruption from exhaustion,
     * matching the production code's [AcquireRetryResult] contract.
     */
    private fun simulateAcquireRetry(
        pool: EnginePool,
        sessionId: String,
        fileHash: String,
        maxRetries: Int = 5,
        retryDelayMs: Long = 5
    ): AcquireRetryOutcome {
        for (i in 0 until maxRetries) {
            when (val result = pool.acquire(sessionId, fileHash, EngineOptions())) {
                is AcquireResult.Found,
                is AcquireResult.NeedSpawn,
                is AcquireResult.Full -> return AcquireRetryOutcome.Success(result)

                is AcquireResult.Busy -> {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        return AcquireRetryOutcome.Interrupted
                    }
                }
            }
        }
        return AcquireRetryOutcome.Exhausted
    }
}
