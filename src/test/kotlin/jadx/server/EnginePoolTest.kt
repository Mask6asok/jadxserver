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
import kotlin.test.*

class EnginePoolTest {
    private lateinit var tempDir: Path
    private lateinit var fileIndex: FileIndex
    private lateinit var enginePool: EnginePool
    private val engine = MockEngine()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-pool")
        fileIndex = FileIndex()
        val poolConfig = PoolConfig(
            maxTotal = 2,
            maxPerFile = 1,
            idleTimeout = Duration.ofMinutes(5)
        )
        enginePool = EnginePool(poolConfig, engine, fileIndex)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testAcquireAndRelease() {
        val testFile = tempDir.resolve("test.apk")
        Files.writeString(testFile, "mock apk content")
        val entry = fileIndex.add(testFile)

        val options = EngineOptions()
        val result1 = enginePool.acquire("session1", entry.hash, options)
        assertTrue(result1 is AcquireResult.NeedSpawn)

        val instance = engine.open(result1.file, result1.options)
        enginePool.insert("session1", entry.hash, instance)

        val result2 = enginePool.acquire("session1", entry.hash, options)
        assertTrue(result2 is AcquireResult.Busy)

        enginePool.release(instance)

        val result3 = enginePool.acquire("session1", entry.hash, options)
        assertTrue(result3 is AcquireResult.Found)
        assertEquals(instance.instanceId, result3.instance.instanceId)
    }

    @Test
    fun testPoolCapacityLimits() {
        val file1 = tempDir.resolve("app1.apk")
        val file2 = tempDir.resolve("app2.apk")
        val file3 = tempDir.resolve("app3.apk")
        Files.writeString(file1, "apk 1")
        Files.writeString(file2, "apk 2")
        Files.writeString(file3, "apk 3")

        val entry1 = fileIndex.add(file1)
        val entry2 = fileIndex.add(file2)
        val entry3 = fileIndex.add(file3)

        val options = EngineOptions()

        val r1 = enginePool.acquire("session1", entry1.hash, options)
        assertTrue(r1 is AcquireResult.NeedSpawn)
        val inst1 = engine.open(r1.file, r1.options)
        enginePool.insert("session1", entry1.hash, inst1)

        val r2 = enginePool.acquire("session1", entry2.hash, options)
        assertTrue(r2 is AcquireResult.NeedSpawn)
        val inst2 = engine.open(r2.file, r2.options)
        enginePool.insert("session1", entry2.hash, inst2)

        val r3 = enginePool.acquire("session1", entry3.hash, options)
        assertTrue(r3 is AcquireResult.Full)
    }

    @Test
    fun testIdleEviction() {
        val testFile = tempDir.resolve("test.apk")
        Files.writeString(testFile, "mock apk" )
        val entry = fileIndex.add(testFile)

        val options = EngineOptions()
        val r1 = enginePool.acquire("session1", entry.hash, options)
        assertTrue(r1 is AcquireResult.NeedSpawn)

        val instance = engine.open(r1.file, r1.options)
        enginePool.insert("session1", entry.hash, instance)
        enginePool.release(instance)

        assertEquals(1, enginePool.instanceCount())

        val evicted = enginePool.evict(Duration.ZERO)
        assertEquals(1, evicted.size)
        assertEquals(instance.instanceId, evicted.first().instanceId)
        assertEquals(0, enginePool.instanceCount())
    }
}
