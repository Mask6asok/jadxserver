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

object XrefTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("class_xrefs", "Find all classes that reference the target class")
            .param("class_name", "string", "Fully qualified target class name", true)
            .param("limit", "number", "Maximum results (default: 100, max: 1000)", false),
        McpToolDef("method_xrefs", "Find callers and callees of a method")
            .param("class_name", "string", "Fully qualified class name", true)
            .param("method_name", "string", "Method name", true)
            .param("direction", "string", "'callers' or 'callees' (default: both)", false)
            .param("limit", "number", "Maximum results per direction (default: 100, max: 1000)", false)
    )

    fun classXrefs(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        val limit = args.getInt("limit", 100).coerceIn(1, 1000)

        val xrefs = apk.getClassXrefs(className, limit)
        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            put("ref_count", JsonPrimitive(xrefs.size))
            put("xrefs", buildJsonArray {
                for (x in xrefs) {
                    add(buildJsonObject {
                        put("class_name", JsonPrimitive(x.className))
                        put("method_name", JsonPrimitive(x.methodName))
                        put("line", JsonPrimitive(x.line))
                    })
                }
            })
        }
    }

    fun methodXrefs(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        val methodName = args.getString("method_name")
            ?: return ToolResult.badParams("Missing required parameter: method_name")
        val direction = args.getString("direction") ?: "both"
        val limit = args.getInt("limit", 100).coerceIn(1, 1000)

        val result = buildJsonObject {
            put("class_name", JsonPrimitive(className))
            put("method_name", JsonPrimitive(methodName))

            if (direction == "callers" || direction == "both") {
                val callers = apk.getMethodXrefs(className, methodName, "to", limit)
                put("callers", buildJsonArray {
                    for (x in callers) {
                        add(buildJsonObject {
                            put("class_name", JsonPrimitive(x.className))
                            put("method_name", JsonPrimitive(x.methodName))
                            put("line", JsonPrimitive(x.line))
                        })
                    }
                })
                put("caller_count", JsonPrimitive(callers.size))
            }

            if (direction == "callees" || direction == "both") {
                val callees = apk.getMethodXrefs(className, methodName, "from", limit)
                put("callees", buildJsonArray {
                    for (x in callees) {
                        add(buildJsonObject {
                            put("class_name", JsonPrimitive(x.className))
                            put("method_name", JsonPrimitive(x.methodName))
                            put("line", JsonPrimitive(x.line))
                        })
                    }
                })
                put("callee_count", JsonPrimitive(callees.size))
            }
        }

        return ToolResult.success { put("xrefs", result) }
    }
}
