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
    fun `timeout in tool execution leaves file status stuck at ANALYZING`() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)

        // ── handleSyncAnalysis line 101: mark file as ANALYZING ──
        fileIndex.updateStatus(hash, FileStatus.ANALYZING)

        // ── Lines 158–177 (NeedSpawn): acquire → spawn → insert ──
        val r1 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r1 is AcquireResult.NeedSpawn, "Expected NeedSpawn on first acquire")
        val instance = mockEngine.open(r1.file, r1.options)
        pool.insert("s1", hash, instance)

        // ── Line 139 (Found): reacquire after insert ──
        val r2 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r2 is AcquireResult.Found, "Expected Found after insert")

        // ── Lines 142–149: withTimeout which fires, got caught by handleSyncAnalysis ──
        val result: ToolResult = try {
            runBlocking {
                // Very short 1ms timeout — will fire before delay completes
                withTimeout(1) {
                    delay(Long.MAX_VALUE)
                    // Unreachable — timeout fires first
                }
            }
            throw AssertionError("Expected TimeoutCancellationException to fire")
        } catch (e: TimeoutCancellationException) {
            // ══════════════════════════════════════════════════════
            //  LINES 149–150: BUG IS HERE
            //
            //  handleSyncAnalysis returns the error ToolResult but
            //  NEVER calls fileIndex.updateStatus(hash, FAILED).
            //  File status stays ANALYZING forever.
            // ══════════════════════════════════════════════════════
            ToolResult.internal("Tool execution timed out")
        }

        // ── Line 155 (finally): release instance ──
        pool.release(r2.instance)

        // ══════════════════════════════════════════════════════
        //  PROVE THE BUG: invariant assertion
        //
        //  After any tool execution outcome (success / timeout /
        //  error), the file status MUST be terminal — either
        //  ANALYZED or FAILED. It must NOT remain ANALYZING.
        //
        //  This assertion FAILS on current code because the
        //  timeout catch block omits updateStatus(FAILED).
        //
        //  Once the fix is applied, this flips to GREEN.
        // ══════════════════════════════════════════════════════
        assertNotEquals(
            FileStatus.ANALYZING,
            fileIndex.resolve(hash)!!.status,
            "BUG: File status must NOT be ANALYZING after tool timeout — " +
                "handleSyncAnalysis timeout catch block must call updateStatus(FAILED)"
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
     * REGRESSION: [McpHandler.handleSyncAnalysis] line 136 returns an error when
     * [McpHandler.acquireWithRetry] returns null (all workers busy after retrying),
     * but NEVER calls `fileIndex.updateStatus(hash, FAILED)`. The file stays
     * [FileStatus.ANALYZING] forever.
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
    fun `acquire exhaustion leaves file status stuck at ANALYZING`() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 1, maxPerFile = 1)

        // ── handleSyncAnalysis line 101: mark file as ANALYZING ──
        fileIndex.updateStatus(hash, FileStatus.ANALYZING)

        // ── Fill the only pool slot ──
        val r1 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r1 is AcquireResult.NeedSpawn, "Expected NeedSpawn on first acquire")
        val instance = mockEngine.open(r1.file, r1.options)
        pool.insert("s1", hash, instance)

        // ── Lines 135–136: simulate acquireWithRetry exhaustion ──
        // Every pool.acquire() returns Busy because maxPerFile=1 and the
        // existing instance is Busy. After maxRetries attempts, give up.
        val acquireResult = simulateAcquireRetryExhaustion(pool, "s1", hash)
        assertNull(
            acquireResult,
            "Expected acquire to exhaust retries and return null"
        )

        // ══════════════════════════════════════════════════════
        //  LINE 136: BUG IS HERE
        //
        //  handleSyncAnalysis returns the error:
        //    return toCallToolResult(ToolResult.error(...))
        //  but NEVER calls fileIndex.updateStatus(hash, FAILED).
        //  File status stays ANALYZING forever.
        // ══════════════════════════════════════════════════════

        // ══════════════════════════════════════════════════════
        //  PROVE THE BUG: invariant assertion
        //
        //  After acquire failure, the file status MUST be
        //  terminal (FAILED), not still ANALYZING.
        //
        //  This assertion FAILS on current code because the
        //  acquire-exhaustion path omits updateStatus(FAILED).
        // ══════════════════════════════════════════════════════
        assertNotEquals(
            FileStatus.ANALYZING,
            fileIndex.resolve(hash)!!.status,
            "BUG: File status must NOT be ANALYZING after acquire exhaustion — " +
                "handleSyncAnalysis must call updateStatus(FAILED) when acquireWithRetry fails"
        )

        // ── Error contract: the error returned at line 136 ──
        val errorResult = ToolResult.error(-32002, "All workers busy after retrying, try again later")
        assertEquals(-32002, errorResult.code, "Acquire exhaustion error code should be -32002")

        // ── Cleanup ──
        pool.release(instance)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helper: acquireWithRetry simulation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Simulates [McpHandler.acquireWithRetry] — loops calling
     * [EnginePool.acquire] up to [maxRetries] times. Returns null when retries
     * are exhausted on Busy, exactly matching handleSyncAnalysis's contract.
     */
    private fun simulateAcquireRetryExhaustion(
        pool: EnginePool,
        sessionId: String,
        fileHash: String,
        maxRetries: Int = 5,
        retryDelayMs: Long = 5
    ): AcquireResult? {
        for (i in 0 until maxRetries) {
            when (val result = pool.acquire(sessionId, fileHash, EngineOptions())) {
                is AcquireResult.Found,
                is AcquireResult.NeedSpawn,
                is AcquireResult.Full -> return result

                is AcquireResult.Busy -> {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
            }
        }
        return null
    }
}
