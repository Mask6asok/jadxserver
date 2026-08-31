package jadx.server

import jadx.server.engine.ProjectSnapshot
import jadx.server.engine.SnapshotClassSummary
import jadx.server.engine.SnapshotMetadata
import jadx.server.server.ApkRuntimeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApkRuntimeRegistryTest {
    @Test
    fun roundTripAndDigestInvalidation() {
        val root = Files.createTempDirectory("snapshot-test")
        try {
            val binary = root.resolve("app.apk")
            Files.writeString(binary, "one")
            val snapshot = ProjectSnapshot(
                fileHash = "abc123",
                apkSha256 = ApkRuntimeRegistry.sha256(binary),
                metadata = SnapshotMetadata("p", "1", 1, 1, 2, 21, 34, listOf("p.X"), emptyList(), emptyList(), emptyList()),
                classSummaries = listOf(SnapshotClassSummary("p.X", "p", true, false, false, false, "java.lang.Object", 2, 0, 0)),
                manifest = "<manifest package=\"p\"/>", sourceDir = root.resolve("code").toString()
            )
            ApkRuntimeRegistry().also { registry ->
                registry.save("abc123", binary, snapshot)
                val reloaded = ApkRuntimeRegistry().load("abc123", binary)
                assertEquals(snapshot, reloaded)
                Files.writeString(binary, "two")
                assertNull(registry.load("abc123", binary))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentLoadSaveAndInvalidateKeepSnapshotAtomic() {
        val root = Files.createTempDirectory("snapshot-concurrency")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val binary = root.resolve("app.apk")
            Files.writeString(binary, "one")
            val snapshot = ProjectSnapshot(
                fileHash = "concurrent",
                apkSha256 = ApkRuntimeRegistry.sha256(binary),
                metadata = SnapshotMetadata("p", "1", 1, 1, 1, 21, 34, emptyList(), emptyList(), emptyList(), emptyList()),
                classSummaries = listOf(SnapshotClassSummary("p.X", "p", true, false, false, false, "java.lang.Object", 1, 0, 0)),
                manifest = "<manifest package=\"p\"/>",
                sourceDir = root.resolve("code").toString(),
            )
            val registry = ApkRuntimeRegistry()
            registry.save("concurrent", binary, snapshot)

            val start = CountDownLatch(1)
            val done = CountDownLatch(8)
            val failures = ConcurrentLinkedQueue<Throwable>()
            repeat(8) { worker ->
                executor.submit {
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        repeat(40) { iteration ->
                            when {
                                worker == 0 && iteration % 7 == 0 -> registry.invalidate("concurrent", binary)
                                iteration % 3 == 0 -> registry.save("concurrent", binary, snapshot)
                                else -> {
                                    val loaded = registry.load("concurrent", binary)
                                    if (loaded != null) assertEquals(snapshot, loaded)
                                }
                            }
                            val cached = registry.get("concurrent", binary)
                            if (cached != null) assertEquals(snapshot, cached)
                        }
                    } catch (error: Throwable) {
                        failures.add(error)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "snapshot workers did not finish")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))

            // A final save must always leave a valid, parseable artifact after the race.
            registry.save("concurrent", binary, snapshot)
            assertEquals(snapshot, registry.load("concurrent", binary))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }
}
