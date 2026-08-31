package jadx.server

import jadx.server.engine.ProjectSnapshot
import jadx.server.engine.SnapshotClassDetail
import jadx.server.engine.SnapshotClassSummary
import jadx.server.engine.SnapshotFieldSummary
import jadx.server.engine.SnapshotMethodSummary
import jadx.server.engine.SnapshotMetadata
import jadx.server.tools.SnapshotTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.nio.file.Files

class SnapshotToolsTest {
    @Test
    fun `snapshot serves immutable class index and cached source`() {
        val root = Files.createTempDirectory("snapshot-tools")
        try {
            val codeRoot = root.resolve("code")
            val source = codeRoot.resolve("sources/p/Main.java")
            Files.createDirectories(source.parent)
            Files.writeString(source, "package p; class Main {}")
            val snapshot = snapshot(codeRoot)

            val classes = SnapshotTools.execute("list_classes", snapshot, JsonObject(emptyMap()))
            assertNotNull(classes)
            assertEquals("1", payload(classes)["count"]?.toString())

            val code = SnapshotTools.execute(
                "get_class_code", snapshot,
                buildJsonObject { put("class_name", "p.Main") },
            )
            assertNotNull(code)
            assertEquals("p.Main", payload(code)["class_name"]?.toString()?.trim('"'))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsupported or incomplete snapshot falls back to engine`() {
        val snapshot = snapshot(null)
        assertNull(
            SnapshotTools.execute(
                "class_info", snapshot,
                buildJsonObject { put("class_name", "p.Main") },
            )
        )
        assertNull(
            SnapshotTools.execute(
                "get_class_code", snapshot,
                buildJsonObject { put("class_name", "p.Main") },
            )
        )
    }

    @Test
    fun `class details and method list are served without an engine`() {
        val detail = SnapshotClassDetail(
            name = "p.Main",
            packageName = "p",
            superclass = "java.lang.Object",
            interfaces = listOf("java.io.Serializable"),
            isAbstract = false,
            isInterface = false,
            isEnum = false,
            accessFlags = "public",
            methods = listOf(
                SnapshotMethodSummary("onCreate", "onCreate(android.os.Bundle)", "void", "(android.os.Bundle)", 1, true, false),
            ),
            fields = listOf(SnapshotFieldSummary("VALUE", "int", true, true, "public static final")),
            innerClasses = emptyList(),
        )
        val snapshot = snapshot(null).copy(classDetails = mapOf("p.Main" to detail))

        val info = SnapshotTools.execute(
            "class_info", snapshot, buildJsonObject { put("class_name", "p.Main") },
        )
        assertNotNull(info)
        assertEquals("p.Main", payload(info)["name"]?.toString()?.trim('"'))

        val methods = SnapshotTools.execute(
            "list_methods", snapshot, buildJsonObject { put("class_name", "p.Main") },
        )
        assertNotNull(methods)
        assertEquals("1", payload(methods)["count"]?.toString())
    }

    private fun snapshot(sourceRoot: java.nio.file.Path?) = ProjectSnapshot(
        fileHash = "hash",
        apkSha256 = "sha",
        metadata = SnapshotMetadata("p", null, null, 1, 1, null, null, emptyList(), emptyList(), emptyList(), emptyList()),
        classSummaries = listOf(SnapshotClassSummary("p.Main", "p", true, false, false, false, "java.lang.Object", 1, 0, 0)),
        sourceDir = sourceRoot?.toString(),
    )

    private fun payload(result: jadx.server.mcp.ToolResult): kotlinx.serialization.json.JsonObject =
        (result as jadx.server.mcp.ToolResult.Success).data
}
