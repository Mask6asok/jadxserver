package jadx.server.tools

import jadx.server.engine.AndroidManifestParser
import jadx.server.engine.ProjectSnapshot
import jadx.server.engine.SnapshotClassSummary
import jadx.server.engine.SnapshotFieldSummary
import jadx.server.engine.SnapshotMethodSummary
import jadx.server.mcp.ToolResult
import jadx.server.server.ProjectCacheLayout
import jadx.server.util.getBoolean
import jadx.server.util.getInt
import jadx.server.util.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read-only operations over [ProjectSnapshot].  A null result means that the
 * requested tool is not represented by the snapshot and must be executed on
 * an exclusive JADX engine.  Returning a normal [ToolResult] means the fast
 * path is authoritative and no mutable JADX object graph was touched.
 */
object SnapshotTools {
    private val supportedTools = setOf(
        "get_manifest",
        "search_manifest_components",
        "list_classes",
        "find_class",
        "get_class_code",
        "class_info",
        "list_methods",
    )

    fun execute(toolName: String, snapshot: ProjectSnapshot, args: JsonObject): ToolResult? {
        if (toolName !in supportedTools) return null
        return when (toolName) {
            "get_manifest" -> getManifest(snapshot)
            "search_manifest_components" -> searchManifest(snapshot, args)
            "list_classes" -> listClasses(snapshot, args)
            "find_class" -> findClass(snapshot, args)
            "get_class_code" -> getClassCode(snapshot, args)
            "class_info" -> classInfo(snapshot, args)
            "list_methods" -> listMethods(snapshot, args)
            else -> null
        }
    }

    private fun getManifest(snapshot: ProjectSnapshot): ToolResult {
        val manifest = snapshot.manifest
            ?: return ToolResult.notFound("AndroidManifest.xml not found")
        return ToolResult.success { put("content", JsonPrimitive(manifest)) }
    }

    private fun searchManifest(snapshot: ProjectSnapshot, args: JsonObject): ToolResult {
        val manifestXml = snapshot.manifest
            ?: return ToolResult.notFound("AndroidManifest.xml not found")
        val query = args.getString("query")
            ?: return ToolResult.badParams("Missing required parameter: query")
        if (query.isEmpty()) return ToolResult.badParams("Parameter 'query' must not be empty")

        val type = args.getString("type") ?: "all"
        val useRegex = args.getBoolean("regex", false)
        val caseSensitive = args.getBoolean("case_sensitive", false)
        val limit = args.getInt("limit", 100).coerceIn(1, 1000)
        val regex = if (useRegex) {
            try {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (e: IllegalArgumentException) {
                return ToolResult.badParams("Invalid regular expression: ${e.message}")
            }
        } else {
            null
        }
        val matches = AndroidManifestParser.searchComponents(
            manifestXml,
            query,
            type = type,
            regex = regex,
            caseSensitive = caseSensitive,
            limit = limit,
        ) ?: return ToolResult.badParams("Invalid component type: $type")

        return ToolResult.success {
            put("query", JsonPrimitive(query))
            put("type", JsonPrimitive(type))
            put("match_count", JsonPrimitive(matches.size))
            put("matches", buildJsonArray { matches.forEach { add(buildJsonObject {
                put("type", JsonPrimitive(it.type.name.lowercase()))
                put("name", JsonPrimitive(it.name))
                put("xml", JsonPrimitive(it.xml))
            }) } })
        }
    }

    private fun listClasses(snapshot: ProjectSnapshot, args: JsonObject): ToolResult {
        val filter = args.getString("package")
        val offset = args.getInt("offset", 0).coerceAtLeast(0)
        val count = args.getInt("count", 100).coerceIn(1, 5000)
        val classes = snapshot.classSummaries
            .asSequence()
            .filter { filter == null || it.name.contains(filter, ignoreCase = true) }
            .drop(offset)
            .take(count)
            .toList()
        return ToolResult.success {
            put("count", JsonPrimitive(classes.size))
            put("offset", JsonPrimitive(offset))
            put("classes", buildJsonArray { classes.forEach { add(it.toJson()) } })
        }
    }

    private fun findClass(snapshot: ProjectSnapshot, args: JsonObject): ToolResult {
        val filter = args.getString("filter")
            ?: return ToolResult.badParams("Missing required parameter: filter")
        val limit = args.getInt("limit", 100).coerceIn(1, 5000)
        val classes = snapshot.classSummaries.asSequence()
            .filter { it.name.contains(filter, ignoreCase = true) || it.name.substringAfterLast('.').contains(filter, ignoreCase = true) }
            .take(limit)
            .toList()
        return ToolResult.success {
            put("filter", JsonPrimitive(filter))
            put("match_count", JsonPrimitive(classes.size))
            put("classes", buildJsonArray {
                classes.forEach {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(it.name))
                        put("package", JsonPrimitive(it.`package`))
                        put("method_count", JsonPrimitive(it.methodCount))
                    })
                }
            })
        }
    }

    private fun classInfo(snapshot: ProjectSnapshot, args: JsonObject): ToolResult? {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        if (snapshot.classSummaries.none { it.name == className }) {
            return ToolResult.notFound("Class not found: $className")
        }
        val detail = snapshot.classDetails[className] ?: return null
        return ToolResult.success {
            put("name", JsonPrimitive(detail.name))
            put("package", JsonPrimitive(detail.packageName))
            put("superclass", JsonPrimitive(detail.superclass))
            put("interfaces", buildJsonArray { detail.interfaces.forEach { add(JsonPrimitive(it)) } })
            put("is_abstract", JsonPrimitive(detail.isAbstract))
            put("is_interface", JsonPrimitive(detail.isInterface))
            put("is_enum", JsonPrimitive(detail.isEnum))
            put("access_flags", JsonPrimitive(detail.accessFlags))
            put("methods", buildJsonArray { detail.methods.forEach { add(it.toJson()) } })
            put("fields", buildJsonArray { detail.fields.forEach { add(it.toJson()) } })
            put("inner_classes", buildJsonArray { detail.innerClasses.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun listMethods(snapshot: ProjectSnapshot, args: JsonObject): ToolResult? {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        if (snapshot.classSummaries.none { it.name == className }) {
            return ToolResult.notFound("Class not found: $className")
        }
        val detail = snapshot.classDetails[className] ?: return null
        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            put("count", JsonPrimitive(detail.methods.size))
            put("methods", buildJsonArray { detail.methods.forEach { add(it.toJson()) } })
        }
    }

    private fun getClassCode(snapshot: ProjectSnapshot, args: JsonObject): ToolResult? {
        val className = args.getString("class_name")
            ?: return ToolResult.badParams("Missing required parameter: class_name")
        if (snapshot.classSummaries.none { it.name == className }) {
            return ToolResult.notFound("Class not found: $className")
        }
        val sourceRoot = snapshot.sourceDir?.let {
            val codeDir = Path.of(it).toAbsolutePath().normalize()
            ProjectCacheLayout(codeDir.parent).sourcesDir.normalize()
        }
            ?: return null
        val sourcePath = sourceRoot.resolve(className.replace('.', '/') + ".java").normalize()
        if (!sourcePath.startsWith(sourceRoot) || !Files.isRegularFile(sourcePath)) {
            // The immutable index is still useful, but source generation may
            // be incomplete. Rehydrate an engine instead of returning a false
            // negative from the fast path.
            return null
        }
        val source = runCatching { Files.readString(sourcePath) }.getOrNull() ?: return null
        return ToolResult.success {
            put("class_name", JsonPrimitive(className))
            put("source", JsonPrimitive(source))
        }
    }

    private fun SnapshotClassSummary.toJson() = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("package", JsonPrimitive(`package`))
        put("method_count", JsonPrimitive(methodCount))
        put("is_inner", JsonPrimitive(isInner))
    }

    private fun SnapshotMethodSummary.toJson() = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("signature", JsonPrimitive(signature))
        put("return_type", JsonPrimitive(returnType))
        put("is_static", JsonPrimitive(isStatic))
        put("param_count", JsonPrimitive(paramCount))
    }

    private fun SnapshotFieldSummary.toJson() = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("type", JsonPrimitive(type))
        put("is_static", JsonPrimitive(isStatic))
        put("is_final", JsonPrimitive(isFinal))
        put("access_flags", JsonPrimitive(accessFlags))
    }
}
