package jadx.server.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Central service for resolving all project cache paths and managing
 * cache-state metadata.
 *
 * Cache directory layout (relative to [cacheDir]):
 *   code/
 *     sources/              -- decompiled Java source files (*.java)
 *   usage/
 *     class/                -- class-level xref cache (JSON)
 *     method/               -- method-level xref cache (JSON)
 *   server/
 *     cache-state.json      -- cache validity metadata (see [CacheState])
 *
 * This is THE authoritative source for cache path resolution across
 * engine and handler flows. No other component should hardcode paths
 * like "project.cache/code" or "project.cache/usage".
 */
class ProjectCacheLayout(val cacheDir: Path) {

    // ── Subdirectory resolvers ──

    /** Code cache root — passed to ProjectDiskCodeCache as cacheRoot. */
    val codeDir: Path get() = cacheDir.resolve("code")

    /** Source files directory for decompiled Java sources. */
    val sourcesDir: Path get() = codeDir.resolve("sources")

    /** Xref usage cache directory (class + method sub-dirs). */
    val usageDir: Path get() = cacheDir.resolve("usage")

    /** JSON cache-state metadata file. */
    val stateFile: Path get() = cacheDir.resolve("server").resolve("cache-state.json")

    // ── Path resolution ──

    /**
     * Resolve the filesystem path for a decompiled class source file.
     * e.g. "jadx.server.Main" -> "<sourcesDir>/jadx/server/Main.java"
     */
    fun sourceFileFor(className: String): Path =
        sourcesDir.resolve(className.replace('.', '/') + ".java")

    /**
     * Resolve the filesystem path for a xref cache file.
     * e.g. kind="class", key="com.example.Foo" -> "<usageDir>/class/com_example_Foo.json"
     */
    fun xrefCacheFile(kind: String, key: String): Path {
        val safeName = buildString {
            key.forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_')
            }
        }
        return usageDir.resolve(kind).resolve("$safeName.json")
    }

    /** Create all required cache subdirectories. */
    fun ensureDirs() {
        Files.createDirectories(sourcesDir)
        Files.createDirectories(usageDir.resolve("class"))
        Files.createDirectories(usageDir.resolve("method"))
        Files.createDirectories(stateFile.parent)
    }

    // ── Cache-state metadata ──

    /**
     * Read and deserialize cache-state metadata from [stateFile].
     * @return [CacheState] if the file exists and is valid JSON, or `null` on any error.
     */
    fun readState(): CacheState? {
        if (!Files.exists(stateFile)) return null
        return try {
            json.decodeFromString<CacheState>(Files.readString(stateFile))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Serialize and write [state] to [stateFile], creating parent directories as needed.
     */
    fun writeState(state: CacheState) {
        Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, json.encodeToString(state))
    }

    /**
     * Validate whether the on-disk cache is still usable for the given
     * decompilation parameters.
     *
     * Returns `true` if [stateFile] exists and ALL required fields in [expected]
     * match exactly — the caller may reuse the existing cache.
     *
     * Returns `false` if any required key is missing, mismatched, or the file
     * does not exist — the caller should invalidate the cache and rebuild.
     */
    fun isValid(expected: CacheState): Boolean {
        val actual = readState() ?: return false
        return actual.schemaVersion == expected.schemaVersion
            && actual.jadxVersion == expected.jadxVersion
            && actual.serverBuildVersion == expected.serverBuildVersion
            && actual.inputHash == expected.inputHash
            && actual.pluginOptionsFingerprint == expected.pluginOptionsFingerprint
            && actual.deobfuscationFlags == expected.deobfuscationFlags
            && actual.classFilter == expected.classFilter
            && actual.xrefMode == expected.xrefMode
    }

    /**
     * Delete the entire cache directory tree.
     */
    fun deleteCache() {
        if (Files.exists(cacheDir)) {
            Files.walk(cacheDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        internal val json: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = false
            encodeDefaults = true
        }
    }
}

/**
 * Schema for `cache-state.json` — persists decompilation parameters so the
 * server can detect when the on-disk cache is stale.
 *
 * @property schemaVersion Schema version for forward compatibility (current: 1).
 * @property jadxVersion Version of jadx-core used when the cache was written.
 * @property serverBuildVersion jadx-server build version.
 * @property inputHash MD5 hash of the input binary file.
 * @property pluginOptionsFingerprint Hash of plugin options map (e.g. MD5 of sorted key=value pairs).
 * @property deobfuscationFlags Whether deobfuscation was enabled.
 * @property classFilter Class filter string (nullable).
 * @property xrefMode Xref mode string ("JADX" or "TEXT").
 * @property lastWrittenAt ISO-8601 timestamp of when the state was written (optional).
 * @property usageFileBytes Total bytes on disk for usage cache files (optional).
 * @property codeCacheBytes Total bytes on disk for code source files (optional).
 */
@Serializable
data class CacheState(
    // ── Required keys ──
    val schemaVersion: Int = 1,
    val jadxVersion: String,
    val serverBuildVersion: String,
    val inputHash: String,
    val pluginOptionsFingerprint: String,
    val deobfuscationFlags: Boolean,
    val classFilter: String?,
    val xrefMode: String,
    // ── Optional keys ──
    val lastWrittenAt: String? = null,
    val usageFileBytes: Long? = null,
    val codeCacheBytes: Long? = null,
)
