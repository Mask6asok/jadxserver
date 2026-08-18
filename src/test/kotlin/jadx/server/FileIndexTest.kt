package jadx.server

import jadx.server.server.FileEntry
import jadx.server.server.FileIndex
import jadx.server.server.FileIndexData
import jadx.server.server.FileStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileIndexTest {
    private lateinit var tempDir: Path
    private lateinit var fileIndex: FileIndex

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-index")
        fileIndex = FileIndex(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testAddAndResolve() {
        val testFile = tempDir.resolve("test.apk")
        Files.writeString(testFile, "mock apk content")

        val entry = fileIndex.add(testFile)
        assertEquals("test.apk", entry.originalName)
        assertEquals(FileStatus.UPLOADED, entry.status)
        assertNotNull(entry.hash)
        assertEquals(7, entry.hash.length)

        val resolved = fileIndex.resolve(entry.hash)
        assertNotNull(resolved)
        assertEquals(entry.md5, resolved.md5)

        val resolvedByMd5 = fileIndex.resolve(entry.md5)
        assertNotNull(resolvedByMd5)
        assertEquals(entry.hash, resolvedByMd5.hash)
    }

    @Test
    fun testUpdateStatus() {
        val testFile = tempDir.resolve("test.apk")
        Files.writeString(testFile, "mock apk content")

        val entry = fileIndex.add(testFile)
        assertEquals(FileStatus.UPLOADED, entry.status)

        fileIndex.updateStatus(entry.hash, FileStatus.ANALYZED)
        val updated = fileIndex.resolve(entry.hash)
        assertNotNull(updated)
        assertEquals(FileStatus.ANALYZED, updated.status)
    }

    @Test
    fun testListAndFilter() {
        val file1 = tempDir.resolve("app-debug.apk")
        val file2 = tempDir.resolve("lib-release.jar")
        Files.writeString(file1, "apk content")
        Files.writeString(file2, "jar content")

        val entry1 = fileIndex.add(file1)
        val entry2 = fileIndex.add(file2)

        val all = fileIndex.list()
        assertEquals(2, all.size)

        val apks = fileIndex.list(typeFilter = ".apk")
        assertEquals(1, apks.size)
        assertEquals(entry1.hash, apks.first().hash)

        val libs = fileIndex.list(nameFilter = "lib")
        assertEquals(1, libs.size)
        assertEquals(entry2.hash, libs.first().hash)
    }

    @Test
    fun testPersistence() {
        val testFile = tempDir.resolve("test.apk")
        Files.writeString(testFile, "mock apk content")

        val entry = fileIndex.add(testFile)
        fileIndex.updateStatus(entry.hash, FileStatus.ANALYZED)

        val restored = FileIndex.loadFromUploadDir(tempDir)
        assertEquals(1, restored.entryCount())

        val resolved = restored.resolve(entry.hash)
        assertNotNull(resolved)
        assertEquals(entry.md5, resolved.md5)
        assertEquals(FileStatus.ANALYZED, resolved.status)
    }

    @Test
    fun testFileListingSortsByNewestAndFormatsLocalAge() {
        val now = Instant.parse("2026-08-17T04:00:00Z")
        writeEntries(
            maintenanceEntry("old", "2026-08-10T04:00:00Z", FileStatus.FAILED),
            maintenanceEntry("new", "2026-08-16T16:00:00Z", FileStatus.ANALYZED),
        )

        val index = FileIndex.loadFromUploadDir(tempDir)
        val items = sortedFileList(index, limit = null, now = now)

        assertEquals(listOf("new", "old"), items.map { it.entry.hash })
        assertEquals("2026-08-17 00:00:00+08:00", items.first().storedAtText)
        assertEquals(0, items.first().ageDays)
        assertEquals(7, items.last().ageDays)
        assertTrue(formatFileList(items).contains("ANALYZED"))
    }

    @Test
    fun testDeleteBeforeKeepsUploadedAndAnalyzingEntries() {
        val now = Instant.parse("2026-08-17T04:00:00Z")
        val oldAnalyzed = maintenanceEntry("old-analyzed", "2026-07-01T04:00:00Z", FileStatus.ANALYZED)
        val oldFailed = maintenanceEntry("old-failed", "2026-07-01T04:00:00Z", FileStatus.FAILED)
        val oldUploaded = maintenanceEntry("old-uploaded", "2026-07-01T04:00:00Z", FileStatus.UPLOADED)
        val oldAnalyzing = maintenanceEntry("old-analyzing", "2026-07-01T04:00:00Z", FileStatus.ANALYZING)
        writeEntries(oldAnalyzed, oldFailed, oldUploaded, oldAnalyzing)
        listOf(oldAnalyzed, oldFailed, oldUploaded, oldAnalyzing).forEach {
            Files.createDirectories(Path.of(it.path).parent)
            Files.writeString(Path.of(it.path), it.hash)
        }

        val index = FileIndex.loadFromUploadDir(tempDir)
        val summary = deleteFilesBefore(index, tempDir, days = 30, now = now)

        assertEquals(2, summary.deleted)
        assertEquals(2, summary.kept)
        assertTrue(summary.failed.isEmpty())
        assertNull(index.resolve(oldAnalyzed.hash))
        assertNull(index.resolve(oldFailed.hash))
        assertNotNull(index.resolve(oldUploaded.hash))
        assertNotNull(index.resolve(oldAnalyzing.hash))
        assertFalse(Files.exists(Path.of(oldAnalyzed.path)))
        assertFalse(Files.exists(Path.of(oldFailed.path)))
        assertTrue(Files.exists(Path.of(oldUploaded.path)))
    }

    @Test
    fun testAddComputesMd5ForMultiChunkAndEmptyFiles() {
        val content = ByteArray(20_000) { (it % 251).toByte() }
        val largeFile = tempDir.resolve("large.bin")
        Files.write(largeFile, content)
        val expected = MessageDigest.getInstance("MD5")
            .digest(content)
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, fileIndex.add(largeFile).md5)

        val emptyFile = tempDir.resolve("empty.bin")
        Files.write(emptyFile, ByteArray(0))
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            fileIndex.add(emptyFile).md5,
        )
    }

    private fun maintenanceEntry(hash: String, storedAt: String, status: FileStatus): FileEntry {
        return FileEntry(
            hash = hash,
            md5 = hash.padEnd(32, '0'),
            originalName = "$hash.apk",
            path = tempDir.resolve("binary").resolve(hash).resolve("$hash.apk").toString(),
            fileSize = hash.length.toLong(),
            storedAt = storedAt,
            status = status,
        )
    }

    private fun writeEntries(vararg entries: FileEntry) {
        Files.writeString(
            tempDir.resolve("store.json"),
            Json { prettyPrint = true }.encodeToString(FileIndexData(entries.toList()))
        )
    }
}
