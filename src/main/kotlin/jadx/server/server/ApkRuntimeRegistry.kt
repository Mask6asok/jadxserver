package jadx.server.server

import jadx.server.engine.ApkMetadata
import jadx.server.engine.ClassInfo
import jadx.server.engine.ClassSummary
import jadx.server.engine.DecompiledApk
import jadx.server.engine.FieldInfo
import jadx.server.engine.MethodInfo
import jadx.server.engine.ProjectSnapshot
import jadx.server.engine.SnapshotClassSummary
import jadx.server.engine.SnapshotClassDetail
import jadx.server.engine.SnapshotFieldSummary
import jadx.server.engine.SnapshotMethodSummary
import jadx.server.engine.SnapshotMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe, single-flight registry for immutable project snapshots. */
class ApkRuntimeRegistry {
    private val snapshots = ConcurrentHashMap<String, ProjectSnapshot>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val validatedFiles = ConcurrentHashMap<String, FileFingerprint>()

    fun get(fileHash: String): ProjectSnapshot? = snapshots[fileHash]

    /** Fast path with integrity validation when the binary is available. */
    fun get(fileHash: String, binaryPath: Path, cacheDir: Path? = null): ProjectSnapshot? {
        val snapshot = snapshots[fileHash]
            ?.takeIf { it.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION }
            ?: return load(fileHash, binaryPath, cacheDir)
        val fingerprint = fingerprint(binaryPath)
        val validated = validatedFiles[fileHash]
        if (validated == fingerprint) return snapshot
        return if (snapshot.apkSha256 == sha256(binaryPath)) {
            validatedFiles[fileHash] = fingerprint
            snapshot
        } else {
            load(fileHash, binaryPath, cacheDir)
        }
    }

    fun load(fileHash: String, binaryPath: Path, cacheDir: Path? = null): ProjectSnapshot? {
        val sha = sha256(binaryPath)
        snapshots[fileHash]
            ?.takeIf { it.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION && it.apkSha256 == sha }
            ?.let { return it }
        synchronized(lockFor(fileHash)) {
            snapshots[fileHash]
                ?.takeIf { it.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION && it.apkSha256 == sha }
                ?.let { return it }
            val path = snapshotPath(binaryPath, cacheDir)
            val loaded = runCatching { json.decodeFromString<ProjectSnapshot>(Files.readString(path)) }.getOrNull()
            if (loaded != null && loaded.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION &&
                loaded.fileHash == fileHash && loaded.apkSha256 == sha) {
                snapshots[fileHash] = loaded
                validatedFiles[fileHash] = fingerprint(binaryPath)
                return loaded
            }
            snapshots.remove(fileHash)
            validatedFiles.remove(fileHash)
            return null
        }
    }

    fun capture(
        fileHash: String,
        binaryPath: Path,
        apk: DecompiledApk,
        cacheDir: Path? = apk.sourceDir?.toAbsolutePath()?.normalize()?.parent,
    ): ProjectSnapshot {
        val fileFingerprint = fingerprint(binaryPath)
        synchronized(lockFor(fileHash)) {
            val sourceDir = apk.sourceDir?.toAbsolutePath()?.normalize()?.toString()
            val existing = snapshots[fileHash]
            if (existing != null &&
                existing.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION &&
                existing.sourceDir == sourceDir &&
                validatedFiles[fileHash] == fileFingerprint
            ) {
                return existing
            }
            val sha = sha256(binaryPath)
            existing
                ?.takeIf {
                    it.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION &&
                        it.apkSha256 == sha && it.sourceDir == sourceDir
                }
                ?.let {
                    validatedFiles[fileHash] = fileFingerprint
                    return it
                }
            val snapshot = ProjectSnapshot(
                fileHash = fileHash,
                apkSha256 = sha,
                metadata = apk.metadata.toSnapshot(),
                classSummaries = apk.listClasses(null, 0, Int.MAX_VALUE).map { it.toSnapshot() },
                manifest = apk.getManifest(),
                sourceDir = sourceDir,
            )
            persist(fileHash, binaryPath, snapshot, cacheDir)
            validatedFiles[fileHash] = fileFingerprint
            return snapshot
        }
    }

    /**
     * Add one visited class's structural metadata to the immutable snapshot.
     * The operation is single-flight per APK and intentionally stores only
     * value DTOs; the live JavaClass graph is never retained by the registry.
     */
    fun captureClassInfo(
        fileHash: String,
        binaryPath: Path,
        apk: DecompiledApk,
        className: String,
        cacheDir: Path? = apk.sourceDir?.toAbsolutePath()?.normalize()?.parent,
    ): ProjectSnapshot? {
        synchronized(lockFor(fileHash)) {
            val base = capture(fileHash, binaryPath, apk, cacheDir)
            val info = apk.getClassInfo(className) ?: return base
            val detail = info.toSnapshot()
            if (base.classDetails[className] == detail) {
                return base
            }
            val updated = base.copy(classDetails = base.classDetails + (className to detail))
            persist(fileHash, binaryPath, updated, cacheDir)
            validatedFiles[fileHash] = fingerprint(binaryPath)
            return updated
        }
    }

    /** Store an already materialized DTO (useful for adapters and lightweight tests). */
    fun save(fileHash: String, binaryPath: Path, snapshot: ProjectSnapshot, cacheDir: Path? = null): ProjectSnapshot {
        require(snapshot.fileHash == fileHash) { "snapshot fileHash does not match requested fileHash" }
        require(snapshot.schemaVersion == ProjectSnapshot.CURRENT_SCHEMA_VERSION) {
            "unsupported snapshot schema: ${snapshot.schemaVersion}"
        }
        require(snapshot.apkSha256 == sha256(binaryPath)) { "snapshot APK digest does not match binary" }
        synchronized(lockFor(fileHash)) {
            persist(fileHash, binaryPath, snapshot, cacheDir)
            validatedFiles[fileHash] = fingerprint(binaryPath)
            return snapshot
        }
    }

    fun invalidate(fileHash: String, binaryPath: Path? = null, cacheDir: Path? = null) {
        synchronized(lockFor(fileHash)) {
            snapshots.remove(fileHash)
            validatedFiles.remove(fileHash)
            binaryPath?.let { runCatching { Files.deleteIfExists(snapshotPath(it, cacheDir)) } }
        }
    }

    /** Drop in-memory immutable snapshots during server shutdown or tests. */
    fun clear() {
        snapshots.clear()
        validatedFiles.clear()
        locks.clear()
    }

    private fun lockFor(fileHash: String): Any = locks.computeIfAbsent(fileHash) { Any() }

    private fun persist(fileHash: String, binaryPath: Path, snapshot: ProjectSnapshot, cacheDir: Path?) {
        writeAtomically(snapshotPath(binaryPath, cacheDir), json.encodeToString(snapshot))
        snapshots[fileHash] = snapshot
    }

    private fun snapshotPath(binaryPath: Path, cacheDir: Path?): Path =
        ProjectCacheLayout(cacheDir ?: ProjectCacheLayout.forBinary(binaryPath).cacheDir).snapshotFile

    private fun fingerprint(path: Path): FileFingerprint = FileFingerprint(
        size = Files.size(path),
        lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
    )

    private fun writeAtomically(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, ".project.snapshot-", ".tmp")
        try {
            Files.writeString(tmp, content)
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
    }

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private data class FileFingerprint(val size: Long, val lastModifiedMillis: Long)
}

private fun ApkMetadata.toSnapshot() = SnapshotMetadata(packageName, versionName, versionCode, classCount, methodCount, minSdk, targetSdk, permissions.toList(), activities.toList(), services.toList(), receivers.toList())
private fun ClassSummary.toSnapshot() = SnapshotClassSummary(name, `package`, isPublic, isAbstract, isInterface, isInner, superClass, methodCount, fieldCount, innerClassCount)
private fun ClassInfo.toSnapshot() = SnapshotClassDetail(
    name = name,
    packageName = `package`,
    superclass = superclass,
    interfaces = interfaces.toList(),
    isAbstract = isAbstract,
    isInterface = isInterface,
    isEnum = isEnum,
    accessFlags = accessFlags,
    methods = methods.map(MethodInfo::toSnapshot),
    fields = fields.map(FieldInfo::toSnapshot),
    innerClasses = innerClasses.toList(),
)
private fun MethodInfo.toSnapshot() = SnapshotMethodSummary(
    name = name,
    signature = signature,
    returnType = returnType,
    parameters = parameters,
    paramCount = paramCount,
    isPublic = isPublic,
    isStatic = isStatic,
)
private fun FieldInfo.toSnapshot() = SnapshotFieldSummary(
    name = name,
    type = type,
    isStatic = isStatic,
    isFinal = isFinal,
    accessFlags = accessFlags,
)
