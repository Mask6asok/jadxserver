package jadx.server.fixture

import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import jadx.server.server.AcquireResult
import jadx.server.server.EnginePool
import jadx.server.server.FileIndex
import jadx.server.server.PoolConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Base class for reliability regression tests that exercise
 * [EnginePool] lifecycle scenarios (pool limits, acquire/release,
 * eviction, engine open failure/delay, file status tracking).
 *
 * Provides:
 * - [tempDir] — temporary directory, cleaned up after each test
 * - [fileIndex] — [FileIndex] rooted at [tempDir]
 * - [mockEngine] — [LifecycleMockEngine] with counters reset before each test
 * - [makePool] — builds an [EnginePool] with the given capacity
 * - [addFixtureFile] — writes a temp file, registers in [fileIndex], returns hash
 * - [spawnAndInsert] — acquires + spawns + inserts an engine instance
 */
abstract class LifecyclePoolTestBase {

    protected lateinit var tempDir: Path
    protected lateinit var fileIndex: FileIndex
    protected val mockEngine = LifecycleMockEngine()

    @BeforeTest
    fun baseSetUp() {
        tempDir = Files.createTempDirectory("jadx-lifecycle-pool-test")
        Files.createDirectories(tempDir.resolve("binary"))
        fileIndex = FileIndex(tempDir)
        mockEngine.resetCounters()
        mockEngine.openDelayMs = 0
        mockEngine.openShouldThrow = null
    }

    @AfterTest
    fun baseTearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ── Helper factories ─────────────────────────────────────────────

    protected fun makePool(
        maxTotal: Int = 2,
        maxPerFile: Int = 1,
        idleTimeout: Duration = Duration.ofMinutes(5)
    ): EnginePool {
        return EnginePool(PoolConfig(maxTotal, maxPerFile, idleTimeout), mockEngine, fileIndex)
    }

    protected fun addFixtureFile(name: String, content: String? = null): String {
        val f = tempDir.resolve(name)
        Files.writeString(f, content ?: "fixture content for $name")
        return fileIndex.add(f).hash
    }

    protected fun spawnAndInsert(
        pool: EnginePool,
        sessionId: String,
        hash: String,
        options: EngineOptions = EngineOptions()
    ): EngineInstance {
        val r = pool.acquire(sessionId, hash, options)
        assertTrue(r is AcquireResult.NeedSpawn, "Expected NeedSpawn but got $r")
        val inst = mockEngine.open(r.file, r.options)
        pool.insert(sessionId, hash, inst)
        return inst
    }
}
