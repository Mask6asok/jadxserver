package jadx.server.engine

import jadx.api.ICodeCache
import jadx.api.ICodeInfo
import jadx.api.impl.SimpleCodeInfo
import java.nio.file.Files
import java.nio.file.Path

class ProjectDiskCodeCache(private val cacheRoot: Path) : ICodeCache {
    private val sourcesRoot = cacheRoot.resolve("sources")

    override fun add(clsFullName: String, codeInfo: ICodeInfo) {
        val file = fileFor(clsFullName)
        Files.createDirectories(file.parent)
        Files.writeString(file, codeInfo.codeStr)
    }

    override fun remove(clsFullName: String) {
        Files.deleteIfExists(fileFor(clsFullName))
    }

    override fun get(clsFullName: String): ICodeInfo {
        return getCode(clsFullName)?.let { SimpleCodeInfo(it) } ?: ICodeInfo.EMPTY
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

    private fun fileFor(clsFullName: String): Path =
        sourcesRoot.resolve(clsFullName.replace('.', '/') + ".java")
}
