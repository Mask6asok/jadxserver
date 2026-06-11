package jadx.server

import jadx.api.impl.SimpleCodeInfo
import jadx.server.engine.ProjectDiskCodeCache
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
