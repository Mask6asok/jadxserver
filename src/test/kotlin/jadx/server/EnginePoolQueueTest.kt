package jadx.server

import jadx.server.engine.EngineOptions
import jadx.server.engine.MockEngine
import jadx.server.server.AcquireResult
import jadx.server.server.EnginePool
import jadx.server.server.FileIndex
import jadx.server.server.PoolConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnginePoolQueueTest {
    private lateinit var root: Path
    private lateinit var pool: EnginePool
    private lateinit var fileHash: String
    private lateinit var engine: MockEngine

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("engine-pool-queue")
        val index = FileIndex()
        val file = root.resolve("app.apk")
        Files.writeString(file, "mock")
        fileHash = index.add(file).hash
        engine = MockEngine()
        pool = EnginePool(
            PoolConfig(maxTotal = 1, maxPerFile = 1, idleTimeout = Duration.ofMinutes(5)),
            engine,
            index,
        )
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `availability wait wakes when busy engine is released`() {
        val first = pool.acquire("s1", fileHash, EngineOptions())
        assertTrue(first is AcquireResult.NeedSpawn)
        val instance = engine.open(first.file, first.options)
        pool.insert("s1", fileHash, instance)

        val ready = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val waiter = Thread {
            ready.countDown()
            result.set(pool.awaitAvailability(Duration.ofSeconds(2)))
        }
        waiter.start()
        assertTrue(ready.await(1, TimeUnit.SECONDS))
        val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (pool.waitingCount() == 0 && System.nanoTime() < waitDeadline) {
            Thread.yield()
        }
        assertTrue(pool.waitingCount() > 0, "waiter did not enter the condition")
        pool.release(instance)
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertTrue(result.get())
        engine.close(instance)
    }

    @Test
    fun `availability wait times out and unregisters waiter`() {
        assertFalse(pool.awaitAvailability(Duration.ofMillis(20)))
        assertEquals(0, pool.waitingCount())
    }

    @Test
    fun `shared preparation admits only one cold open before enabling second slot`() {
        val preparedPool = EnginePool(
            PoolConfig(
                maxTotal = 2,
                maxPerFile = 2,
                idleTimeout = Duration.ofMinutes(5),
                singleFlightPreparation = true,
            ),
            engine,
            FileIndex().also { it.add(root.resolve("app.apk")) },
        )
        val first = preparedPool.acquire("s1", fileHash, EngineOptions())
        assertTrue(first is AcquireResult.NeedSpawn)
        assertTrue(preparedPool.acquire("s2", fileHash, EngineOptions()) is AcquireResult.Busy)

        val instance = engine.open(first.file, first.options)
        preparedPool.insert("s1", fileHash, instance)
        assertTrue(preparedPool.acquire("s2", fileHash, EngineOptions()) is AcquireResult.Busy)

        preparedPool.markPrepared(fileHash)
        assertTrue(preparedPool.acquire("s2", fileHash, EngineOptions()) is AcquireResult.NeedSpawn)
        preparedPool.cancelSpawn("s2", fileHash)
        preparedPool.release(instance)
        engine.close(instance)
    }

}
