package jadx.server.tools

import jadx.server.engine.DecompiledApk
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.util.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

object ResourceTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("get_manifest", "Return AndroidManifest.xml content"),
        McpToolDef("get_resource", "Return specific resource file content by path")
            .param("path", "string", "Resource path (e.g. res/values/strings.xml)", true),
        McpToolDef("list_resources", "List all resource files with types")
            .param("type", "string", "Filter by resource type (e.g. xml, png)", false)
    )

    fun getManifest(apk: DecompiledApk, args: JsonObject): ToolResult {
        val manifest = apk.getManifest()
            ?: return ToolResult.notFound("AndroidManifest.xml not found")
        return ToolResult.success {
            put("content", JsonPrimitive(manifest))
        }
    }

    fun getResource(apk: DecompiledApk, args: JsonObject): ToolResult {
        val path = args.getString("path")
            ?: return ToolResult.badParams("Missing required parameter: path")

        val content = apk.getResource(path)
            ?: return ToolResult.notFound("Resource not found: $path")

        return ToolResult.success {
            put("path", JsonPrimitive(path))
            put("content", JsonPrimitive(content))
        }
    }

    fun listResources(apk: DecompiledApk, args: JsonObject): ToolResult {
        val typeFilter = args.getString("type")
        val resources = apk.listResources()
        val filtered = if (typeFilter != null) {
            resources.filter { it.type.equals(typeFilter, ignoreCase = true) }
        } else {
            resources
        }

        return ToolResult.success {
            put("count", JsonPrimitive(filtered.size))
            put("resources", buildJsonArray {
                for (r in filtered) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(r.name))
                        put("type", JsonPrimitive(r.type))
                        put("path", JsonPrimitive(r.path))
                    })
                }
            })
        }
    }
}
