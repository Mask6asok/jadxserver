package jadx.server.engine

import jadx.api.ICodeCache
import jadx.api.ICodeInfo
import jadx.api.impl.SimpleCodeInfo
import jadx.core.dex.nodes.RootNode
import jadx.server.server.ProjectCacheLayout
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

class ProjectDiskCodeCache(
    private val cacheRoot: Path,
    projectLayout: ProjectCacheLayout? = null,
    private val requireMetadata: Boolean = false,
) : ICodeCache {
    // Production callers pass the project layout whose codeDir is [cacheRoot].
    // The fallback keeps the small standalone cache unit-test friendly by
    // placing its sidecars below the supplied temporary root.
    private val layout = projectLayout ?: ProjectCacheLayout(cacheRoot)
    private val metadata = ProjectDiskCodeMetadata(layout)
    private val sourcesRoot = cacheRoot.resolve("sources")
    private val normalizedSourcesRoot = sourcesRoot.normalize()

    override fun add(clsFullName: String, codeInfo: ICodeInfo) {
        val file = fileFor(clsFullName)
        Files.createDirectories(file.parent)
        val code = codeInfo.codeStr
        writeSourceAtomically(file, code)
        try {
            metadata.write(metadata.fileFor(clsFullName), code, codeInfo)
        } catch (error: Exception) {
            // A plain source cache is still useful when metadata persistence
            // is unavailable. Never leave an older sidecar paired with a
            // newly generated source file.
            runCatching { Files.deleteIfExists(metadata.fileFor(clsFullName)) }
            throw error
        }
    }

    override fun remove(clsFullName: String) {
        Files.deleteIfExists(fileFor(clsFullName))
        Files.deleteIfExists(metadata.fileFor(clsFullName))
    }

    override fun get(clsFullName: String): ICodeInfo {
        val code = getCode(clsFullName) ?: return ICodeInfo.EMPTY
        metadata.read(metadata.fileFor(clsFullName), code)?.let { return it }
        // In xref-enabled engines a legacy/source-only entry is not a
        // complete cache hit: force ClassNode to regenerate it and persist a
        // metadata completion marker. Before bindRoot(), keep the historical
        // plain-source behavior needed during decompiler bootstrap.
        if (requireMetadata && metadata.isBound()) {
            return ICodeInfo.EMPTY
        }
        return SimpleCodeInfo(code)
    }

    /** Bind the current JADX object graph used to rehydrate metadata refs. */
    fun bindRoot(root: RootNode) {
        metadata.bind(root)
    }

    /** Return true only when the source has a valid metadata completion marker. */
    fun hasMetadata(clsFullName: String): Boolean {
        val code = getCode(clsFullName) ?: return false
        return metadata.isCompatible(metadata.fileFor(clsFullName), code)
    }

    override fun getCode(clsFullName: String): String? {
        val file = fileFor(clsFullName)
        if (!Files.exists(file)) return null
        return try {
            Files.readString(file)
        } catch (_: Exception) {
            null
        }
    }

    override fun contains(clsFullName: String): Boolean = Files.exists(fileFor(clsFullName))

    override fun close() {
    }

    private fun fileFor(clsFullName: String): Path {
        require(clsFullName.isNotBlank()) {
            "class name escapes cache root: $clsFullName"
        }
        require(!clsFullName.contains('/') && !clsFullName.contains('\\') && !containsEncodedPathSeparator(clsFullName)) {
            "class name escapes cache root: $clsFullName"
        }
        val resolved = sourcesRoot.resolve(clsFullName.replace('.', '/') + ".java").normalize()
        require(resolved.startsWith(normalizedSourcesRoot)) {
            "class name escapes cache root: $clsFullName"
        }
        return resolved
    }

    private fun containsEncodedPathSeparator(clsFullName: String): Boolean {
        val lowerName = clsFullName.lowercase()
        return "%2f" in lowerName || "%5c" in lowerName
    }

    private fun writeSourceAtomically(file: Path, code: String) {
        val temp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.newOutputStream(temp, WRITE, CREATE, TRUNCATE_EXISTING).bufferedWriter().use { writer ->
                writer.write(code)
            }
            try {
                Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
