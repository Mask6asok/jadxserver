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

object ClassTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("list_classes", "List classes with optional package filter and pagination")
            .param("package", "string", "Filter by package name prefix", false)
            .param("offset", "number", "Pagination offset (default: 0)", false)
            .param("count", "number", "Page size (default: 100, max: 5000)", false),
        McpToolDef("get_class_code", "Return full decompiled source code for a class")
            .param("class_name", "string", "Fully qualified class name", true),
        McpToolDef("class_info", "Return class structure: methods, fields, inheritance, interfaces, inner classes")
            .param("class_name", "string", "Fully qualified class name", true)
    )

    fun listClasses(apk: DecompiledApk, args: JsonObject): ToolResult {
        val packageFilter = args.getString("package")
        val offset = args.getInt("offset", 0)
        val count = args.getInt("count", 100).coerceIn(1, 5000)

        val classes = apk.listClasses(packageFilter, offset, count)
        return ToolResult.success {
            put("count", JsonPrimitive(classes.size))
            put("offset", JsonPrimitive(offset))
            put("classes", buildJsonArray {
                for (c in classes) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(c.name))
                        put("package", JsonPrimitive(c.`package`))
                        put("method_count", JsonPrimitive(c.methodCount))
                        put("is_inner", JsonPrimitive(c.isInner))
                    })
                }
            })
        }
    }

    fun getClassCode(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")

        val code = apk.getClassCode(className)
            ?: return ToolResult.notFound("Class not found: $className")

        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            put("source", JsonPrimitive(code))
        }
    }

    fun classInfo(apk: DecompiledApk, args: JsonObject): ToolResult {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")

        val info = apk.getClassInfo(className)
            ?: return ToolResult.notFound("Class not found: $className")

        return ToolResult.success {
            put("name", JsonPrimitive(info.name))
            put("package", JsonPrimitive(info.`package`))
            put("superclass", JsonPrimitive(info.superclass))
            put("interfaces", buildJsonArray { info.interfaces.forEach { add(JsonPrimitive(it)) } })
            put("is_abstract", JsonPrimitive(info.isAbstract))
            put("is_interface", JsonPrimitive(info.isInterface))
            put("is_enum", JsonPrimitive(info.isEnum))
            put("access_flags", JsonPrimitive(info.accessFlags))
            put("methods", buildJsonArray {
                for (m in info.methods) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(m.name))
                        put("signature", JsonPrimitive(m.signature))
                        put("return_type", JsonPrimitive(m.returnType))
                        put("is_static", JsonPrimitive(m.isStatic))
                        put("param_count", JsonPrimitive(m.paramCount))
                    })
                }
            })
            put("fields", buildJsonArray {
                for (f in info.fields) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(f.name))
                        put("type", JsonPrimitive(f.type))
                        put("is_static", JsonPrimitive(f.isStatic))
                        put("is_final", JsonPrimitive(f.isFinal))
                        put("access_flags", JsonPrimitive(f.accessFlags))
                    })
                }
            })
            put("inner_classes", buildJsonArray { info.innerClasses.forEach { add(JsonPrimitive(it)) } })
        }
    }
}
