package jadx.server.tools

import jadx.server.engine.DecompiledApk
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.util.getString
import jadx.server.util.getBoolean
import jadx.server.util.getInt
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
            .param("type", "string", "Filter by resource type (e.g. xml, png)", false),
        McpToolDef("search_resource", "Search decoded resource text, including AndroidManifest.xml and XML generated from resources.arsc")
            .param("query", "string", "Text or regular expression to find", true)
            .param("regex", "boolean", "Interpret query as a regular expression (default: false)", false)
            .param("case_sensitive", "boolean", "Case-sensitive matching (default: false)", false)
            .param("limit", "number", "Maximum matches (default: 100, max: 5000)", false)
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

    fun searchResource(apk: DecompiledApk, args: JsonObject): ToolResult {
        val query = args.getString("query")
            ?: return ToolResult.badParams("Missing required parameter: query")
        if (query.isEmpty()) return ToolResult.badParams("Parameter 'query' must not be empty")

        val useRegex = args.getBoolean("regex", false)
        val caseSensitive = args.getBoolean("case_sensitive", false)
        val limit = args.getInt("limit", 100).coerceIn(1, 5000)
        val regex = if (useRegex) {
            try {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: IllegalArgumentException) {
                return ToolResult.badParams("Invalid regular expression: ${e.message}")
            }
        } else {
            null
        }

        val matches = apk.searchResources(query, limit, regex, caseSensitive)
        return ToolResult.success {
            put("query", JsonPrimitive(query))
            put("match_count", JsonPrimitive(matches.size))
            put("matches", buildJsonArray {
                for (match in matches) {
                    add(buildJsonObject {
                        put("path", JsonPrimitive(match.path))
                        put("type", JsonPrimitive(match.type))
                        put("line", JsonPrimitive(match.line))
                        put("column", JsonPrimitive(match.column))
                        put("content", JsonPrimitive(match.content))
                    })
                }
            })
        }
    }
}
