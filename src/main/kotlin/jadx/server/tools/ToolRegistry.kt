package jadx.server.tools

import jadx.server.engine.DecompiledApk
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.server.ServerState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Central registry that maps tool names to handler functions.
 * Two categories: server tools (operate on server state) and analysis tools (operate on decompiled APK).
 */
class ToolRegistry private constructor(
    val serverTools: Map<String, ServerToolHandler>,
    val analysisTools: Map<String, AnalysisToolHandler>,
    val analysisToolWeights: Map<String, ToolWeight>,
    val allDefinitions: List<McpToolDef>
) {
    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)

    fun interface ServerToolHandler {
        fun handle(args: JsonObject, sessionId: String, state: ServerState): ToolResult
    }

    fun interface AnalysisToolHandler {
        fun handle(apk: DecompiledApk, args: JsonObject): ToolResult
    }

    val analysisToolNames: Set<String> get() = analysisTools.keys
    val serverToolNames: Set<String> get() = serverTools.keys

    fun analysisToolWeight(name: String): ToolWeight? = analysisToolWeights[name]

    fun definitionFor(name: String): McpToolDef? = allDefinitions.find { it.name == name }

    fun executeAnalysis(name: String, apk: DecompiledApk, args: JsonObject): ToolResult {
        val handler = analysisTools[name]
            ?: return ToolResult.error(-32601, "Unknown analysis tool: $name")
        val normalizedArgs = normalizeMisplacedDescription(name, args)
        if (normalizedArgs !== args) {
            logger.warn("[ANALYSIS] Corrected mislabeled tool argument for {}: {} -> {}", name, compactArgs(args), compactArgs(normalizedArgs))
        }
        val start = System.currentTimeMillis()
        val result = try {
            handler.handle(apk, normalizedArgs)
        } catch (e: Exception) {
            logger.warn("[ANALYSIS] {} {}ms args={} -> ERROR: {}", name, System.currentTimeMillis() - start, compactArgs(normalizedArgs), e.message)
            ToolResult.internal("Tool execution error in $name: ${e.message}")
        }
        logger.info("[ANALYSIS] {} {}ms args={} -> {}", name, System.currentTimeMillis() - start, compactArgs(normalizedArgs), compactResult(result, normalizedArgs))
        return result
    }

    /** Repair the unambiguous `<required parameter>: <value>` client serialization mistake. */
    private fun normalizeMisplacedDescription(name: String, args: JsonObject): JsonObject {
        val definition = definitionFor(name) ?: return args
        val missingRequired = definition.params.filter { it.required && it.name !in args }
        if (missingRequired.size != 1 || "description" !in args) return args

        val description = args["description"] as? JsonPrimitive ?: return args
        if (!description.isString) return args
        val separator = description.content.indexOf(':')
        if (separator < 1) return args

        val label = description.content.substring(0, separator).trim()
        val value = description.content.substring(separator + 1).trim()
        val parameter = missingRequired.single()
        if (!label.equals(parameter.name, ignoreCase = true) || value.isEmpty()) return args

        return buildJsonObject {
            for ((key, element) in args) {
                if (key != "description") put(key, element)
            }
            put(parameter.name, JsonPrimitive(value))
        }
    }

    fun executeServer(name: String, args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val handler = serverTools[name]
            ?: return ToolResult.error(-32601, "Unknown server tool: $name")
        val start = System.currentTimeMillis()
        val result = try {
            handler.handle(args, sessionId, state)
        } catch (e: Exception) {
            logger.warn("[SERVER] {} {}ms session={} args={} -> ERROR: {}", name, System.currentTimeMillis() - start, sessionId.take(12), compactArgs(args), e.message)
            ToolResult.internal("Tool execution error in $name: ${e.message}")
        }
        logger.info("[SERVER] {} {}ms session={} args={} -> {}", name, System.currentTimeMillis() - start, sessionId.take(12), compactArgs(args), compactResult(result, args))
        return result
    }

    private fun compactArgs(args: JsonObject): String {
        val s = args.toString()
        return if (s.length <= 150) s else s.take(147) + "..."
    }

    private val meaningfulKeys = setOf(
        "match_count", "count", "ref_count", "class_count", "method_count",
        "status", "ready", "closed_count", "remaining",
        "package_name", "query", "pattern", "filter", "file_hash",
        "class_name", "method_name", "name", "tool"
    )

    private fun compactResult(result: ToolResult, args: JsonObject = JsonObject(emptyMap())): String = when (result) {
        is ToolResult.Success -> {
            val argsKeys = args.keys
            val parts = mutableListOf<String>()
            for (key in meaningfulKeys) {
                if (key in argsKeys) continue
                val v = result.data[key] ?: continue
                val s = when (v) {
                    is JsonPrimitive -> v.content
                    is kotlinx.serialization.json.JsonArray -> "[${v.size}]"
                    else -> continue
                }
                parts += "$key=$s"
                if (parts.size >= 4) break
            }
            if (parts.isEmpty()) "OK" else "OK(${parts.joinToString(" ")})"
        }
        is ToolResult.Error -> {
            val msg = result.message.take(60)
            "ERR(code=${result.code} ${if (msg.isNotEmpty()) msg else ""})"
        }
        else -> result.toString().take(100)
    }

    companion object {
        enum class ToolWeight {
            LIGHT,
            HEAVY,
        }

        fun build(state: ServerState): ToolRegistry {
            val serverHandlers = linkedMapOf<String, ServerToolHandler>()
            val analysisHandlers = linkedMapOf<String, AnalysisToolHandler>()
            val analysisWeights = linkedMapOf<String, ToolWeight>()
            val defs = mutableListOf<McpToolDef>()

            // ── Server tools ──
            for (def in ServerTools.definitions()) defs += def
            serverHandlers["upload_file"] = ServerToolHandler(ServerTools::uploadFile)
            serverHandlers["save_project"] = ServerToolHandler(ServerTools::saveProject)
            serverHandlers["list_files"] = ServerToolHandler(ServerTools::listFiles)
            serverHandlers["list_instances"] = ServerToolHandler(ServerTools::listInstances)
            serverHandlers["server_health"] = ServerToolHandler(ServerTools::serverHealth)
            serverHandlers["tool_catalog"] = ServerToolHandler { args, sid, st ->
                ServerTools.toolCatalog(args, sid, st, defs)
            }
            serverHandlers["tool_help"] = ServerToolHandler { args, sid, st ->
                ServerTools.toolHelp(args, defs)
            }
            serverHandlers["task_status"] = ServerToolHandler(ServerTools::taskStatus)
            serverHandlers["wait_for_analysis"] = ServerToolHandler(ServerTools::waitForAnalysis)
            serverHandlers["cleanup_session_workers"] = ServerToolHandler(ServerTools::cleanupSessionWorkers)

            // ── Analysis tools ──
            fun regAnalysis(weight: ToolWeight, list: List<McpToolDef>, vararg pairs: Pair<String, AnalysisToolHandler>) {
                defs += list
                for ((name, handler) in pairs) {
                    analysisHandlers[name] = handler
                    analysisWeights[name] = weight
                }
            }

            regAnalysis(ToolWeight.HEAVY, CoreTools.definitions(),
                "decompile_apk" to AnalysisToolHandler(CoreTools::decompileApk),
                "survey" to AnalysisToolHandler(CoreTools::survey),
                "analysis_status" to AnalysisToolHandler(CoreTools::analysisStatus),
            )

            analysisWeights["analysis_status"] = ToolWeight.LIGHT

            regAnalysis(ToolWeight.LIGHT, ClassTools.definitions(),
                "list_classes" to AnalysisToolHandler(ClassTools::listClasses),
                "get_class_code" to AnalysisToolHandler(ClassTools::getClassCode),
                "class_info" to AnalysisToolHandler(ClassTools::classInfo),
            )

            regAnalysis(ToolWeight.LIGHT, MethodTools.definitions(),
                "get_method_code" to AnalysisToolHandler(MethodTools::getMethodCode),
                "get_smali" to AnalysisToolHandler(MethodTools::getSmali),
                "list_methods" to AnalysisToolHandler(MethodTools::listMethods),
            )

            regAnalysis(ToolWeight.HEAVY, SearchTools.definitions(),
                "search_code" to AnalysisToolHandler(SearchTools::searchCode),
                "search_string" to AnalysisToolHandler(SearchTools::searchString),
                "find_class" to AnalysisToolHandler(SearchTools::findClass),
            )

            analysisWeights["find_class"] = ToolWeight.LIGHT

            regAnalysis(ToolWeight.LIGHT, ResourceTools.definitions(),
                "get_manifest" to AnalysisToolHandler(ResourceTools::getManifest),
                "get_manifest_summary" to AnalysisToolHandler(ResourceTools::getManifestSummary),
                "list_manifest_permissions" to AnalysisToolHandler(ResourceTools::listManifestPermissions),
                "list_manifest_components" to AnalysisToolHandler(ResourceTools::listManifestComponents),
                "search_manifest_components" to AnalysisToolHandler(ResourceTools::searchManifestComponents),
                "list_manifest_intent_filters" to AnalysisToolHandler(ResourceTools::listManifestIntentFilters),
                "get_manifest_entrypoints" to AnalysisToolHandler(ResourceTools::getManifestEntrypoints),
                "get_resource" to AnalysisToolHandler(ResourceTools::getResource),
                "list_resources" to AnalysisToolHandler(ResourceTools::listResources),
                "search_resource" to AnalysisToolHandler(ResourceTools::searchResource),
            )

            analysisWeights["search_resource"] = ToolWeight.HEAVY

            regAnalysis(ToolWeight.HEAVY, XrefTools.definitions(),
                "class_xrefs" to AnalysisToolHandler(XrefTools::classXrefs),
                "method_xrefs" to AnalysisToolHandler(XrefTools::methodXrefs),
            )

            return ToolRegistry(serverHandlers, analysisHandlers, analysisWeights, defs)
        }
    }
}
