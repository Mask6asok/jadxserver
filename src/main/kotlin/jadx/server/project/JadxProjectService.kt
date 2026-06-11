package jadx.server.project

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class JadxProjectService {
    fun load(projectFile: Path): JadxProjectFile {
        val content = Files.readString(projectFile)
        return json.decodeFromString(JadxProjectFile.serializer(), content)
    }

    fun save(projectFile: Path, project: JadxProjectFile) {
        Files.createDirectories(projectFile.parent)
        Files.writeString(projectFile, json.encodeToString(JadxProjectFile.serializer(), project))
    }

    fun createDefault(binaryPath: Path, cacheDir: Path, projectFile: Path): JadxProjectFile {
        val parent = projectFile.parent.toAbsolutePath().normalize()
        val binaryRef = relativizeOrAbsolute(parent, binaryPath)
        val cacheRef = relativizeOrAbsolute(parent, cacheDir)
        return JadxProjectFile(
            files = listOf(binaryRef),
            cacheDir = cacheRef
        )
    }

    fun resolveProjectBinary(projectFile: Path, project: JadxProjectFile): Path {
        val fileRef = project.files.firstOrNull()
            ?: throw IllegalArgumentException("Project file contains no input files")
        return resolveAgainstProject(projectFile, fileRef)
    }

    fun resolveProjectCacheDir(projectFile: Path, project: JadxProjectFile): Path? {
        val cacheRef = project.cacheDir ?: return null
        return resolveAgainstProject(projectFile, cacheRef)
    }

    private fun resolveAgainstProject(projectFile: Path, value: String): Path {
        val path = Path.of(value)
        if (path.isAbsolute) {
            return path.normalize()
        }
        return projectFile.parent.resolve(path).normalize()
    }

    private fun relativizeOrAbsolute(baseDir: Path, target: Path): String {
        val normalizedTarget = target.toAbsolutePath().normalize()
        return try {
            baseDir.relativize(normalizedTarget).toString()
        } catch (_: Exception) {
            normalizedTarget.toString()
        }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
