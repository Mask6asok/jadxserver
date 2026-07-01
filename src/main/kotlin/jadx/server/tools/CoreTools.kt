package jadx.server.tools

import jadx.server.engine.DecompiledApk
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.server.FileStatus
import jadx.server.server.ServerState
import jadx.server.util.getBoolean
import jadx.server.util.getInt
import jadx.server.util.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

object CoreTools {

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("decompile_apk", "Decompile APK and return summary metadata"),
        McpToolDef("survey", "Get comprehensive binary overview including metadata, top classes, and resources")
            .param("detail_level", "string", "'standard' or 'minimal' (default: standard)", false),
        McpToolDef("analysis_status", "Check current decompilation status for a file")
    )

    fun decompileApk(apk: DecompiledApk, args: JsonObject): ToolResult {
        val meta = apk.metadata
        val warmup = apk.warmupCodeCache()
        return ToolResult.success {
            put("package_name", JsonPrimitive(meta.packageName))
            put("class_count", JsonPrimitive(meta.classCount))
            put("method_count", JsonPrimitive(meta.methodCount))
            put("permissions", buildJsonArray { meta.permissions.forEach { add(JsonPrimitive(it)) } })
            put("activities", buildJsonArray { meta.activities.forEach { add(JsonPrimitive(it)) } })
            put("services", buildJsonArray { meta.services.forEach { add(JsonPrimitive(it)) } })
            put("receivers", buildJsonArray { meta.receivers.forEach { add(JsonPrimitive(it)) } })
            put("min_sdk", JsonPrimitive(meta.minSdk))
            put("target_sdk", JsonPrimitive(meta.targetSdk))
            put("version_name", JsonPrimitive(meta.versionName))
            put("version_code", JsonPrimitive(meta.versionCode))
            put("code_cache", buildJsonObject {
                put("total_classes", JsonPrimitive(warmup.totalClasses))
                put("generated_classes", JsonPrimitive(warmup.generatedClasses))
                put("cached_classes", JsonPrimitive(warmup.cachedClasses))
                put("failed_classes", JsonPrimitive(warmup.failedClasses))
                put("ready", JsonPrimitive(warmup.failedClasses == 0))
            })
        }
    }

    fun survey(apk: DecompiledApk, args: JsonObject): ToolResult {
        val detailLevel = args.getString("detail_level") ?: "standard"
        val meta = apk.metadata
        val classes = apk.listClasses(null, 0, if (detailLevel == "minimal") 10 else 50)
        val resources = apk.listResources()

        return ToolResult.success {
            put("metadata", buildJsonObject {
                put("package_name", JsonPrimitive(meta.packageName))
                put("class_count", JsonPrimitive(meta.classCount))
                put("method_count", JsonPrimitive(meta.methodCount))
                put("min_sdk", JsonPrimitive(meta.minSdk))
                put("target_sdk", JsonPrimitive(meta.targetSdk))
                put("version_name", JsonPrimitive(meta.versionName))
                put("version_code", JsonPrimitive(meta.versionCode))
            })
            put("top_classes", buildJsonArray {
                for (c in classes) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(c.name))
                        put("package", JsonPrimitive(c.`package`))
                        put("method_count", JsonPrimitive(c.methodCount))
                    })
                }
            })
            put("permissions", buildJsonArray { meta.permissions.forEach { add(JsonPrimitive(it)) } })
            put("resource_count", JsonPrimitive(resources.size))
            put("resource_types", buildJsonArray {
                resources.groupBy { it.type }.keys.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    fun analysisStatus(apk: DecompiledApk, args: JsonObject): ToolResult {
        val meta = apk.metadata
        return ToolResult.success {
            put("status", JsonPrimitive("analyzed"))
            put("package_name", JsonPrimitive(meta.packageName))
            put("class_count", JsonPrimitive(meta.classCount))
            put("method_count", JsonPrimitive(meta.methodCount))
        }
    }

    fun analysisStatus(state: ServerState, fileHash: String): ToolResult {
        val entry = state.fileIndex.resolve(fileHash)
            ?: return ToolResult.notFound("File not found: $fileHash")

        return ToolResult.success {
            put("file_hash", JsonPrimitive(entry.hash))
            put("md5", JsonPrimitive(entry.md5))
            put("name", JsonPrimitive(entry.originalName))
            put("status", JsonPrimitive(entry.status.name.lowercase()))
            put("analyzed", JsonPrimitive(entry.status == FileStatus.ANALYZED))
        }
    }
}
