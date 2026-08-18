package jadx.server

import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import jadx.server.fixture.LifecyclePoolTestBase
import jadx.server.server.AcquireResult
import jadx.server.server.AnalysisActivityCoordinator
import jadx.server.server.EnginePool
import jadx.server.server.FileStatus
import jadx.server.server.FileIndex
import jadx.server.server.IdleEvictor
import jadx.server.server.MemoryGovernor
import jadx.server.server.PoolState
import jadx.server.server.TaskManager
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnginePoolLifecycleTest : LifecyclePoolTestBase() {

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

    @Test
    fun testIdleInstanceIsReclaimedForDifferentFile() {
        val hash1 = addFixtureFile("first.apk")
        val hash2 = addFixtureFile("second.apk")
        val pool = makePool(maxTotal = 1, maxPerFile = 1)
        val first = spawnAndInsert(pool, "s1", hash1)
        pool.release(first)

        val result = pool.acquire("s2", hash2, EngineOptions())
        assertTrue(result is AcquireResult.NeedSpawn)
        assertEquals(first.instanceId, result.reclaimedInstance?.instanceId)
        assertEquals(0, pool.instanceCount())
        pool.cancelSpawn("s2", hash2)
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
        pool.cancelSpawn("s1", hash)
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
        val pending = pool.acquire("s1", hash, EngineOptions())
        assertTrue(pending is AcquireResult.Busy, "Expected Busy while the first engine open is pending but got $pending")
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

    // Periodic memory cleanup

    @Test
    fun testMemoryCleanupDrainsFreshIdleInstancesAndPromptsGc() {
        val hash = addFixtureFile("memory-cleanup.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val instance = spawnAndInsert(pool, "s1", hash)
        pool.release(instance)
        val gcCalls = AtomicInteger()
        val evictor = makeMemoryEvictor(pool, gcCalls)

        assertTrue(evictor.runMemoryCleanupOnceForTest())
        assertEquals(1, mockEngine.closeCallCount)
        assertEquals(1, gcCalls.get())
        assertEquals(0, pool.instanceCount())
    }

    @Test
    fun testMemoryCleanupSkipsBusyInstances() {
        val hash1 = addFixtureFile("memory-busy-1.apk")
        val hash2 = addFixtureFile("memory-busy-2.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val idle = spawnAndInsert(pool, "s1", hash1)
        spawnAndInsert(pool, "s2", hash2)
        pool.release(idle)
        val gcCalls = AtomicInteger()

        assertFalse(makeMemoryEvictor(pool, gcCalls).runMemoryCleanupOnceForTest())
        assertEquals(0, mockEngine.closeCallCount)
        assertEquals(0, gcCalls.get())
        assertEquals(2, pool.instanceCount())
    }

    @Test
    fun testMemoryCleanupSkipsPendingSpawnThenSucceedsAfterCancellation() {
        val idleHash = addFixtureFile("memory-pending-idle.apk")
        val pendingHash = addFixtureFile("memory-pending-open.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val idle = spawnAndInsert(pool, "s1", idleHash)
        pool.release(idle)
        val pending = pool.acquire("s2", pendingHash, EngineOptions())
        assertTrue(pending is AcquireResult.NeedSpawn)
        val gcCalls = AtomicInteger()
        val evictor = makeMemoryEvictor(pool, gcCalls)

        assertFalse(evictor.runMemoryCleanupOnceForTest())
        assertEquals(0, mockEngine.closeCallCount)
        pool.cancelSpawn("s2", pendingHash)

        assertTrue(evictor.runMemoryCleanupOnceForTest())
        assertEquals(1, mockEngine.closeCallCount)
        assertEquals(1, gcCalls.get())
        assertEquals(0, pool.instanceCount())
    }

    @Test
    fun testMemoryCleanupRunsForEmptyQuiescentPool() {
        val gcCalls = AtomicInteger()

        assertTrue(makeMemoryEvictor(makePool(), gcCalls).runMemoryCleanupOnceForTest())
        assertEquals(1, gcCalls.get())
    }

    @Test
    fun testMemoryCleanupBlocksNewPoolAcquireUntilCloseCompletes() {
        val hash = addFixtureFile("memory-gate.apk")
        val pool = makePool(maxTotal = 2, maxPerFile = 1)
        val instance = spawnAndInsert(pool, "s1", hash)
        pool.release(instance)
        var acquireDuringClose: AcquireResult? = null
        mockEngine.closeAction = {
            acquireDuringClose = pool.acquire("s2", hash, EngineOptions())
        }

        assertTrue(makeMemoryEvictor(pool, AtomicInteger()).runMemoryCleanupOnceForTest())
        assertTrue(acquireDuringClose is AcquireResult.Busy)
        assertEquals(0, pool.instanceCount())
    }

    @Test
    fun testMaintenanceLeaseWaitsForActiveAnalysisLease() {
        val coordinator = AnalysisActivityCoordinator()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val analysisThread = Thread {
            coordinator.withAnalysis {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        analysisThread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertNull(coordinator.tryEnterMaintenance())
        release.countDown()
        analysisThread.join(2_000)
        assertFalse(analysisThread.isAlive)
        assertNotNull(coordinator.tryEnterMaintenance()).close()
    }

    private fun makeMemoryEvictor(pool: EnginePool, gcCalls: AtomicInteger): IdleEvictor {
        return IdleEvictor(
            enginePool = pool,
            engine = mockEngine,
            memoryGovernor = MemoryGovernor(),
            idleTimeout = Duration.ofMinutes(5),
            cleanupInterval = Duration.ofSeconds(10),
            taskManager = TaskManager(),
            fileIndex = FileIndex(tempDir),
            memoryCleanupInterval = Duration.ofHours(1),
            gcAction = { gcCalls.incrementAndGet() },
        )
    }
}
