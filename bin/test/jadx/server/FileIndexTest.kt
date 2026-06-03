package jadx.server

import jadx.server.server.FileIndex
import jadx.server.server.FileStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

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
}
