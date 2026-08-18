package jadx.server

import jadx.server.server.FileEntry
import jadx.server.server.FileIndex
import jadx.server.server.FileStatus
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DISPLAY_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val DISPLAY_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ssXXX")
    .withZone(DISPLAY_ZONE)

internal data class FileListItem(
    val entry: FileEntry,
    val storedAt: Instant?,
    val storedAtText: String,
    val ageDays: Long,
)

internal data class FileDeletionSummary(
    val deleted: Int,
    val kept: Int,
    val failed: List<String>,
)

internal fun sortedFileList(
    fileIndex: FileIndex,
    limit: Int?,
    now: Instant = Instant.now(),
): List<FileListItem> {
    require(limit == null || limit >= 0) { "LIMIT must be non-negative" }
    val today = now.atZone(DISPLAY_ZONE).toLocalDate()
    return fileIndex.list()
        .map { entry ->
            val storedAt = parseStoredAt(entry)
            val storedDate = storedAt?.atZone(DISPLAY_ZONE)?.toLocalDate()
            FileListItem(
                entry = entry,
                storedAt = storedAt,
                storedAtText = storedAt?.let(DISPLAY_FORMATTER::format) ?: entry.storedAt,
                ageDays = storedDate?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) } ?: 0,
            )
        }
        .sortedWith(compareByDescending<FileListItem> { it.storedAt ?: Instant.MIN }.thenBy { it.entry.hash })
        .let { items -> if (limit == null) items else items.take(limit) }
}

internal fun deleteFilesBefore(
    fileIndex: FileIndex,
    uploadDir: Path,
    days: Long,
    now: Instant = Instant.now(),
): FileDeletionSummary {
    require(days >= 0) { "DAYS must be non-negative" }
    val cutoff = now.minus(days, ChronoUnit.DAYS)
    val managedRoot = uploadDir.toAbsolutePath().normalize()
    val candidates = fileIndex.list().filter { entry ->
        entry.status == FileStatus.ANALYZED || entry.status == FileStatus.FAILED
    }

    var deleted = 0
    val failures = mutableListOf<String>()
    for (entry in candidates) {
        val storedAt = parseStoredAt(entry)
        if (storedAt == null || !storedAt.isBefore(cutoff)) {
            continue
        }
        try {
            deleteManagedPath(entry.path, managedRoot)
            entry.projectFilePath?.let { deleteManagedPath(it, managedRoot) }
            entry.cacheDirPath?.let { deleteManagedPath(it, managedRoot) }
            if (fileIndex.remove(entry.hash) != null) {
                deleted++
            }
        } catch (e: Exception) {
            failures += "${entry.hash}: ${e.message ?: e::class.simpleName}"
        }
    }

    return FileDeletionSummary(
        deleted = deleted,
        kept = fileIndex.entryCount(),
        failed = failures,
    )
}

internal fun formatFileList(items: List<FileListItem>): String = buildString {
    appendLine("hash\tstatus\tstored_at_utc+8\tage_days\tname")
    for (item in items) {
        val entry = item.entry
        append(entry.hash)
            .append('\t')
            .append(entry.status.name)
            .append('\t')
            .append(item.storedAtText)
            .append('\t')
            .append(item.ageDays)
            .append('\t')
            .appendLine(entry.originalName)
    }
}

private fun parseStoredAt(entry: FileEntry): Instant? = runCatching {
    Instant.parse(entry.storedAt)
}.getOrNull()

private fun deleteManagedPath(rawPath: String, managedRoot: Path) {
    val path = Path.of(rawPath).toAbsolutePath().normalize()
    require(path != managedRoot && path.startsWith(managedRoot)) {
        "refusing to delete path outside upload directory: $path"
    }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return
    }
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    } else {
        Files.deleteIfExists(path)
    }
}
