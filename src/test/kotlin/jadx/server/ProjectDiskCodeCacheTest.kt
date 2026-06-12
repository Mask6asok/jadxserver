package jadx.server

import jadx.api.impl.SimpleCodeInfo
import jadx.server.config.ServerConfig
import jadx.server.engine.ProjectDiskCodeCache
import jadx.server.mcp.FailedRunCleanupContext
import jadx.server.fixture.LifecycleMockEngine
import jadx.server.mcp.McpHandler
import jadx.server.server.FileStatus
import jadx.server.server.ServerState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProjectDiskCodeCacheTest {
    private lateinit var tempDir: Path
    private lateinit var cache: ProjectDiskCodeCache

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("jadx-test-code-cache")
        cache = ProjectDiskCodeCache(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testValidClassNamesMapCorrectly() {
        val validCases = listOf(
            "com.example.Foo" to "sources/com/example/Foo.java",
            "Foo" to "sources/Foo.java",
            "a.b.c.D" to "sources/a/b/c/D.java",
            "a.b.C" to "sources/a/b/C.java",
        )

        validCases.forEach { (className, expectedRelativePath) ->
            val code = "class ${className.substringAfterLast('.')} {}"
            cache.add(className, SimpleCodeInfo(code))

            val expectedPath = tempDir.resolve(expectedRelativePath)
            assertTrue(Files.exists(expectedPath), "expected cache file for $className")
            assertEquals(code, Files.readString(expectedPath))
            assertTrue(cache.contains(className))
            assertEquals(code, cache.getCode(className))
            assertEquals(code, cache.get(className).codeStr)
        }
    }

    @Test
    fun testEscapeAttemptsThrowAndCannotWriteOutsideCacheRoot() {
        val outsideFile = tempDir.parent.resolve("escape.java")
        Files.deleteIfExists(outsideFile)

        listOf("../escape", "..%2f..%2fetc", "/tmp/evil", "").forEach { className ->
            expectIllegalArgument(className) {
                cache.add(className, SimpleCodeInfo("bad"))
            }
        }

        assertFalse(Files.exists(outsideFile), "escape attempt must not create files outside cache root")
    }

    @Test
    fun testEscapeAttemptsThrowAndCannotDeleteOutsideCacheRoot() {
        val validClass = "com.example.Foo"
        cache.add(validClass, SimpleCodeInfo("class Foo {}"))
        val validFile = tempDir.resolve("sources/com/example/Foo.java")
        assertTrue(Files.exists(validFile))

        val outsideFile = tempDir.parent.resolve("escape.java")
        Files.writeString(outsideFile, "keep me")

        listOf("../escape", "..%2f..%2fetc", "/tmp/evil", "").forEach { className ->
            expectIllegalArgument(className) {
                cache.remove(className)
            }
            expectIllegalArgument(className) {
                cache.contains(className)
            }
            expectIllegalArgument(className) {
                cache.getCode(className)
            }
            expectIllegalArgument(className) {
                cache.get(className)
            }
        }

        assertTrue(Files.exists(validFile), "malicious remove must not affect valid cache file")
        assertTrue(Files.exists(outsideFile), "malicious remove must not delete outside cache root")
        assertEquals("keep me", Files.readString(outsideFile))
    }

    @Test
    fun testFailedAnalysisCleansGeneratedArtifactsButPreservesUploadedBinary() {
        val uploadDir = Files.createTempDirectory(tempDir, "uploads")
        val mockEngine = LifecycleMockEngine(openShouldThrow = "simulated open failure")
        val state = ServerState(ServerConfig(uploadDir = uploadDir), mockEngine)
        try {
            val apkFile = tempDir.resolve("fixture.apk")
            Files.writeString(apkFile, "apk bytes")
            val entry = state.fileIndex.add(apkFile, uploadDir)
            val binaryPath = Path.of(entry.path)
            val binaryDir = binaryPath.parent
            val projectFile = binaryDir.resolve("project.jadx")
            val codeDir = binaryDir.resolve("project.cache").resolve("code")
            val cacheDir = codeDir.parent
            val sourceFile = codeDir.resolve("sources/com/example/Foo.java")

            Files.createDirectories(sourceFile.parent)
            Files.writeString(projectFile, "generated project")
            Files.writeString(sourceFile, "class Foo {}")
            val handler = McpHandler(state)

            state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
            state.fileIndex.updateProjectPaths(entry.hash, projectFile, cacheDir)
            handler.cleanupFailedRunArtifacts(
                FailedRunCleanupContext(
                    hash = entry.hash,
                    originalProjectFilePath = projectFile.toString(),
                    originalCacheDirPath = cacheDir.toString(),
                    projectFilePath = projectFile,
                    cacheDirPath = cacheDir,
                    generatedCodeCacheDirPath = codeDir,
                    projectFileExistedAtStart = false,
                    cacheDirExistedAtStart = false,
                    generatedCodeCacheDirExistedAtStart = false
                )
            )

            assertTrue(Files.exists(binaryPath), "uploaded APK must be preserved")
            assertFalse(Files.exists(projectFile), "generated project file must be removed after failed run")
            assertFalse(Files.exists(codeDir), "generated source cache must be removed after failed run")
            assertEquals(null, state.fileIndex.resolve(entry.hash)?.projectFilePath)
            assertEquals(null, state.fileIndex.resolve(entry.hash)?.cacheDirPath)
        } finally {
            state.shutdown()
            uploadDir.toFile().deleteRecursively()
        }
    }

    private fun expectIllegalArgument(className: String, action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException for '$className'")
        } catch (e: IllegalArgumentException) {
            assertEquals("class name escapes cache root: $className", e.message)
            assertNotNull(e.message)
        }
    }
}
