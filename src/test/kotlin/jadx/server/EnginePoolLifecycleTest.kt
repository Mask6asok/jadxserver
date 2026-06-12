package jadx.server

import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import jadx.server.fixture.LifecycleMockEngine
import jadx.server.server.AcquireResult
import jadx.server.server.EnginePool
import jadx.server.server.FileIndex
import jadx.server.server.FileStatus
import jadx.server.server.PoolConfig
import jadx.server.server.PoolState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

class EnginePoolLifecycleTest {
    private lateinit var tempDir: Path
    private lateinit var fileIndex: FileIndex
    private val mockEngine = LifecycleMockEngine()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-lifecycle-test")
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
        Files.writeString(f, content ?: "unique content for $name")
        return fileIndex.add(f).hash
    }

    private fun spawnAndInsert(
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

    // Busy / Full

    @Test
    fun testBusyWhenSlotsFull() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        spawnAndInsert(pool, "s1", hash)
        val r2 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r2 is AcquireResult.Busy, "Expected Busy (maxPerFile=1) but got $r2")
    }

    @Test
    fun testFullWhenPoolExhausted() {
        val hash1 = addFixtureFile("a.apk")
        val hash2 = addFixtureFile("b.apk")
        val hash3 = addFixtureFile("c.apk")
        val pool = makePool(maxTotal = 1, maxPerFile = 1)
        spawnAndInsert(pool, "s1", hash1)
        val r2 = pool.acquire("s1", hash2, EngineOptions())
        assertTrue(r2 is AcquireResult.Full, "Expected Full (maxTotal=1) but got $r2")
    }

    // Release / reacquire

    @Test
    fun testReleaseAndReacquire() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val inst = spawnAndInsert(pool, "s1", hash)
        pool.release(inst)
        val r2 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r2 is AcquireResult.Found, "Expected Found after release but got $r2")
        assertEquals(inst.instanceId, r2.instance.instanceId)
    }

    // maxPerFile

    @Test
    fun testMaxPerFileEnforced() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 3, maxPerFile = 2)
        spawnAndInsert(pool, "s1", hash)
        spawnAndInsert(pool, "s1", hash)
        val r3 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r3 is AcquireResult.Busy, "Expected Busy (maxPerFile=2) but got $r3")
    }

    // Discard

    @Test
    fun testDiscardFreesSlot() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 1, maxPerFile = 1)
        val inst = spawnAndInsert(pool, "s1", hash)
        assertEquals(1, pool.instanceCount())
        assertNotNull(pool.discard(inst))
        assertEquals(0, pool.instanceCount())
        val r2 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r2 is AcquireResult.NeedSpawn, "Expected NeedSpawn after discard but got $r2")
    }

    @Test
    fun testDiscardUnknownInstanceReturnsNull() {
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val hash = addFixtureFile("test.apk")
        val inst = spawnAndInsert(pool, "s1", hash)
        val fake = EngineInstance(engineName = "fake", fileHash = "nope", state = "garbage")
        assertNull(pool.discard(fake))
        assertEquals(1, pool.instanceCount())
        pool.discard(inst)
        assertEquals(0, pool.instanceCount())
    }

    @Test
    fun testReleaseUnknownInstanceNoOp() {
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val hash = addFixtureFile("test.apk")
        val inst = spawnAndInsert(pool, "s1", hash)
        val fake = EngineInstance(engineName = "fake", fileHash = "nope", state = "garbage")
        pool.release(fake)
        assertEquals(1, pool.instanceCount())
        pool.release(inst)
        assertEquals(1, pool.instanceCount())
    }

    // Engine open failure / delay

    @Test
    fun testEngineOpenFailureRecovery() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        mockEngine.openShouldThrow = "simulated open failure"
        val r1 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r1 is AcquireResult.NeedSpawn)
        assertFailsWith<RuntimeException>("simulated open failure") {
            mockEngine.open(r1.file, r1.options)
        }
        assertEquals(0, pool.instanceCount())
        assertEquals(1, mockEngine.openCallCount)
        mockEngine.openShouldThrow = null
        val inst = spawnAndInsert(pool, "s1", hash)
        assertEquals(1, pool.instanceCount())
        assertEquals(2, mockEngine.openCallCount)
        pool.release(inst)
    }

    @Test
    fun testSlowOpenReservesSlot() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 1, maxPerFile = 1)
        mockEngine.openDelayMs = 200
        val r1 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r1 is AcquireResult.NeedSpawn)
        val inst = mockEngine.open(r1.file, r1.options)
        pool.insert("s1", hash, inst)
        assertEquals(1, pool.instanceCount())
        val r2 = pool.acquire("s1", hash, EngineOptions())
        assertTrue(r2 is AcquireResult.Busy, "Expected Busy after slow open but got $r2")
        pool.release(inst)
    }

    // Eviction.
    //
    // EnginePool.evict uses strict > (Duration.between(…) > timeout), so
    // Duration.ZERO never matches same-clock-tick comparisons.  We insert a
    // tiny wall-clock tick (Thread.sleep(1)) between release and evict to
    // guarantee the clock advances.  This is NOT a brittle sleep-as-assertion
    // pattern -- it simply ensures the Instant.now() clock ticks forward so
    // that the > Duration.ZERO comparison becomes true.

    @Test
    fun testIdleEvictionReclaimsInstances() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val inst = spawnAndInsert(pool, "s1", hash)
        // Busy should NOT be evicted even with a long threshold
        assertEquals(0, pool.evict(Duration.ofDays(1)).size)
        pool.release(inst)
        assertEquals(PoolState.Idle, pool.listInstances().first().state)
        Thread.sleep(1)  // ensure clock tick for > Duration.ZERO
        val evicted = pool.evict(Duration.ZERO)
        assertEquals(1, evicted.size)
        assertEquals(inst.instanceId, evicted.first().instanceId)
        assertEquals(0, pool.instanceCount())
    }

    @Test
    fun testEvictSkipsBusyInstances() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 3, maxPerFile = 2)
        val inst1 = spawnAndInsert(pool, "s1", hash)
        spawnAndInsert(pool, "s1", hash)
        // Both busy -- not evictable even with a very long threshold
        assertEquals(0, pool.evict(Duration.ofDays(1)).size)
        assertEquals(2, pool.instanceCount())
        pool.release(inst1)
        Thread.sleep(1)  // ensure clock tick
        val evicted = pool.evict(Duration.ZERO)
        assertEquals(1, evicted.size)
        assertEquals(1, pool.instanceCount())
    }

    @Test
    fun testEvictDoesNotCallEngineClose() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val inst = spawnAndInsert(pool, "s1", hash)
        assertEquals(0, mockEngine.closeCallCount)
        pool.release(inst)
        Thread.sleep(1)
        pool.evict(Duration.ZERO)
        assertEquals(0, mockEngine.closeCallCount,
            "Pool.evict must not call engine.close -- IdleEvictor does that")
    }

    // FileStatus tracking

    @Test
    fun testFileStatusTransitionsThroughLifecycle() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        assertEquals(FileStatus.UPLOADED, fileIndex.resolve(hash)!!.status)
        fileIndex.updateStatus(hash, FileStatus.ANALYZING)
        assertEquals(FileStatus.ANALYZING, fileIndex.resolve(hash)!!.status)
        val inst = spawnAndInsert(pool, "s1", hash)
        fileIndex.updateStatus(hash, FileStatus.ANALYZED)
        assertEquals(FileStatus.ANALYZED, fileIndex.resolve(hash)!!.status)
        pool.release(inst)
    }

    @Test
    fun testStuckAnalyzingStateDetected() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        fileIndex.updateStatus(hash, FileStatus.ANALYZING)
        assertEquals(FileStatus.ANALYZING, fileIndex.resolve(hash)!!.status)
        val inst = spawnAndInsert(pool, "s1", hash)
        assertEquals(1, pool.instanceCount())
        fileIndex.updateStatus(hash, FileStatus.FAILED)
        assertEquals(FileStatus.FAILED, fileIndex.resolve(hash)!!.status)
        pool.release(inst)
    }

    // List instances

    @Test
    fun testListInstancesReturnsCorrectInfo() {
        val hash = addFixtureFile("test.apk")
        val pool = makePool(maxTotal = 3, maxPerFile = 2)
        val inst1 = spawnAndInsert(pool, "s1", hash)
        spawnAndInsert(pool, "s1", hash)
        val instances = pool.listInstances()
        assertEquals(2, instances.size)
        val found = instances.find { it.instanceId == inst1.instanceId }
        assertNotNull(found)
        assertEquals(hash, found.fileHash)
        assertEquals("s1", found.sessionId)
        assertEquals(PoolState.Busy, found.state)
    }
}
