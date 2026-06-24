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

object MethodTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("get_method_code", "Return decompiled source for a specific method")
            .param("class_name", "string", "Fully qualified class name", true)
            .param("method_name", "string", "Method name", true)
            .param("signature", "string", "Method signature for disambiguation (optional)", false),
        McpToolDef("get_smali", "Return class Smali or the exact bytecode block for a method")
            .param("class_name", "string", "Fully qualified class name", true)
            .param("method_name", "string", "Method name; omit to return the complete class", false)
            .param("signature", "string", "Method signature for overload disambiguation", false),
        McpToolDef("list_methods", "List all methods of a class with signatures")
            .param("class_name", "string", "Fully qualified class name", true)
    )

    fun getMethodCode(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        val methodName = args.getString("method_name")
            ?: return ToolResult.badParams("Missing required parameter: method_name")
        val signature = args.getString("signature")

        val code = try { apk.getMethodCode(className, methodName, signature) } catch (_: Exception) { null }
        if (code != null && !isFailedDecompilation(code)) {
            return ToolResult.success {
                put("class_name", JsonPrimitive(className))
                put("method_name", JsonPrimitive(methodName))
                put("format", JsonPrimitive("java"))
                put("fallback", JsonPrimitive(false))
                put("source", JsonPrimitive(code))
            }
        }

        val smali = try { apk.getSmali(className, methodName, signature) } catch (_: Exception) { null }
            ?: return ToolResult.notFound("Method not found or Smali unavailable: $className.$methodName")

        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            put("method_name", JsonPrimitive(methodName))
            put("format", JsonPrimitive("smali"))
            put("fallback", JsonPrimitive(true))
            put("source", JsonPrimitive(smali))
        }
    }

    fun getSmali(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        val methodName = args.getString("method_name")
        val signature = args.getString("signature")

        val smali = try { apk.getSmali(className, methodName, signature) } catch (_: Exception) { null }
            ?: return ToolResult.notFound(
                if (methodName == null) "Class not found or Smali unavailable: $className"
                else "Method not found or Smali unavailable: $className.$methodName"
            )

        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            if (methodName != null) put("method_name", JsonPrimitive(methodName))
            put("format", JsonPrimitive("smali"))
            put("source", JsonPrimitive(smali))
        }
    }

    private fun isFailedDecompilation(code: String): Boolean {
        if (code.isBlank()) return true
        return FAILED_DECOMPILATION_MARKERS.any(code::contains)
    }

    private val FAILED_DECOMPILATION_MARKERS = listOf(
        "JADX ERROR:",
        "Method not decompiled:",
        "Code decompiled incorrectly, please refer to instructions dump."
    )

    fun listMethods(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")

        val methods = apk.listMethods(className)
            ?: return ToolResult.notFound("Class not found: $className")

        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            put("count", JsonPrimitive(methods.size))
            put("methods", buildJsonArray {
                for (m in methods) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(m.name))
                        put("signature", JsonPrimitive(m.signature))
                        put("return_type", JsonPrimitive(m.returnType))
                        put("is_static", JsonPrimitive(m.isStatic))
                        put("param_count", JsonPrimitive(m.paramCount))
                    })
                }
            })
        }
    }
}
