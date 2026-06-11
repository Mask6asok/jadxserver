package jadx.server.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class FileStatus {
    UPLOADED,
    ANALYZING,
    ANALYZED,
    FAILED
}

@Serializable
data class FileEntry(
    val hash: String,
    val md5: String,
    val originalName: String,
    val path: String,
    val fileSize: Long,
    val storedAt: String,
    var status: FileStatus = FileStatus.UPLOADED,
    var projectFilePath: String? = null,
    var cacheDirPath: String? = null,
    var entryType: String = "binary"
)

@Serializable
data class FileIndexData(
    val entries: List<FileEntry> = emptyList()
)

class FileIndex(private val uploadDir: Path? = null) {
    private val entries = ConcurrentHashMap<String, FileEntry>()

    /**
     * Add a file to the index. If [moveToDir] is provided, the file is copied into
     * an MD5-based subdirectory (moveToDir/binary/<md5>/<originalName>) to prevent
     * name collisions between different files with the same filename.
     * The original source file is preserved — only an indexed copy is placed in the target directory.
     */
    fun add(file: Path, moveToDir: Path? = null): FileEntry {
        val bytes = Files.readAllBytes(file)
        val md5 = computeMd5(bytes)
        val hash = md5.substring(0, 7)
        val originalName = file.fileName.toString()
        val fileSize = Files.size(file)

        val finalPath = if (moveToDir != null) {
            val md5Dir = moveToDir.resolve("binary").resolve(md5)
            Files.createDirectories(md5Dir)
            val target = md5Dir.resolve(originalName)
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            target
        } else {
            file
        }

        val entry = FileEntry(
            hash = hash,
            md5 = md5,
            originalName = originalName,
            path = finalPath.toAbsolutePath().normalize().toString(),
            fileSize = fileSize,
            storedAt = Instant.now().toString(),
            status = FileStatus.UPLOADED
        )
        entries[hash] = entry
        autoPersist()
        return entry
    }

    fun remove(hash: String): FileEntry? {
        val removed = entries.remove(hash)
        if (removed != null) autoPersist()
        return removed
    }

    fun resolve(hashOrMd5: String): FileEntry? {
        entries[hashOrMd5]?.let { return it }
        return entries.values.find { it.md5 == hashOrMd5 }
    }

    fun list(
        nameFilter: String? = null,
        typeFilter: String? = null,
        md5Filter: String? = null
    ): List<FileEntry> {
        return entries.values.filter { entry ->
            (nameFilter == null || entry.originalName.contains(nameFilter, ignoreCase = true)) &&
                (typeFilter == null || entry.originalName.endsWith(typeFilter, ignoreCase = true)) &&
                (md5Filter == null || entry.md5.startsWith(md5Filter, ignoreCase = true))
        }
    }

    fun updateStatus(hash: String, status: FileStatus) {
        entries[hash]?.status = status
        autoPersist()
    }

    fun updateProjectPaths(hash: String, projectFilePath: Path?, cacheDirPath: Path?) {
        val entry = entries[hash] ?: return
        entry.projectFilePath = projectFilePath?.toAbsolutePath()?.normalize()?.toString()
        entry.cacheDirPath = cacheDirPath?.toAbsolutePath()?.normalize()?.toString()
        autoPersist()
    }

    fun persist(uploadDir: Path) {
        val dir = uploadDir
        val storeFile = dir.resolve("store.json")
        val data = FileIndexData(entries.values.toList())
        val jsonString = json.encodeToString(data)
        Files.writeString(storeFile, jsonString)
    }

    private fun autoPersist() {
        val dir = uploadDir ?: return
        persist(dir)
    }

    fun entryCount(): Int = entries.size

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun loadFromUploadDir(uploadDir: Path): FileIndex {
            val index = FileIndex(uploadDir)
            val storeFile = uploadDir.resolve("store.json")
            if (Files.exists(storeFile)) {
                val content = Files.readString(storeFile)
                val data = json.decodeFromString<FileIndexData>(content)
                for (entry in data.entries) {
                    index.entries[entry.hash] = entry
                }
            }
            return index
        }

        private fun computeMd5(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(bytes)
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
