package jadx.server.tools

import jadx.server.engine.DecompiledApk
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.util.getInt
import jadx.server.util.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

object SearchTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("search_code", "Regex/text search across all decompiled source code")
            .param("pattern", "string", "Search pattern (regex supported)", true)
            .param("limit", "number", "Maximum results (default: 100, max: 1000)", false),
        McpToolDef("search_string", "Search string constants in the APK")
            .param("query", "string", "String content to find", true)
            .param("limit", "number", "Maximum results (default: 100, max: 5000)", false)
            .param("case_sensitive", "boolean", "Case-sensitive matching (default: false)", false),
        McpToolDef("find_class", "Find classes by name fragment or pattern")
            .param("filter", "string", "Name fragment or pattern to match", true)
            .param("limit", "number", "Maximum results (default: 100, max: 5000)", false)
    )

    fun searchCode(apk: DecompiledApk, args: JsonObject): ToolResult {
        val pattern = args.getString("pattern")
            ?: return ToolResult.badParams("Missing required parameter: pattern")
        val limit = args.getInt("limit", 100).coerceIn(1, 1000)

        val matches = apk.searchCode(pattern, limit)
        return ToolResult.success {
            put("pattern", JsonPrimitive(pattern))
            put("match_count", JsonPrimitive(matches.size))
            put("matches", buildJsonArray {
                for (m in matches) {
                    add(buildJsonObject {
                        put("class_name", JsonPrimitive(m.className))
                        put("line", JsonPrimitive(m.line))
                        put("content", JsonPrimitive(m.content))
                    })
                }
            })
        }
    }

    fun searchString(apk: DecompiledApk, args: JsonObject): ToolResult {
        val query = args.getString("query")
            ?: return ToolResult.badParams("Missing required parameter: query")
        val limit = args.getInt("limit", 100).coerceIn(1, 5000)

        val matches = apk.searchString(query, limit)
        return ToolResult.success {
            put("query", JsonPrimitive(query))
            put("match_count", JsonPrimitive(matches.size))
            put("matches", buildJsonArray {
                for (m in matches) {
                    add(buildJsonObject {
                        put("class_name", JsonPrimitive(m.className))
                        put("line", JsonPrimitive(m.line))
                        put("content", JsonPrimitive(m.content))
                    })
                }
            })
        }
    }

    fun findClass(apk: DecompiledApk, args: JsonObject): ToolResult {
        val filter = args.getString("filter")
            ?: return ToolResult.badParams("Missing required parameter: filter")
        val limit = args.getInt("limit", 100).coerceIn(1, 5000)

        val classes = apk.findClass(filter, limit)
        return ToolResult.success {
            put("filter", JsonPrimitive(filter))
            put("match_count", JsonPrimitive(classes.size))
            put("classes", buildJsonArray {
                for (c in classes) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(c.name))
                        put("package", JsonPrimitive(c.`package`))
                        put("method_count", JsonPrimitive(c.methodCount))
                    })
                }
            })
        }
    }
}
