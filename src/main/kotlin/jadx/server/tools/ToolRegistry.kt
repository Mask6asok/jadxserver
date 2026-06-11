package jadx.server.tools

import jadx.server.config.TransportMode
import jadx.server.engine.DecompiledApk
import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.server.ServerState
import kotlinx.serialization.json.JsonObject

/**
 * Central registry that maps tool names to handler functions.
 * Two categories: server tools (operate on server state) and analysis tools (operate on decompiled APK).
 */
class ToolRegistry private constructor(
    val serverTools: Map<String, ServerToolHandler>,
    val analysisTools: Map<String, AnalysisToolHandler>,
    val allDefinitions: List<McpToolDef>
) {
    fun interface ServerToolHandler {
        fun handle(args: JsonObject, sessionId: String, state: ServerState): ToolResult
    }

    fun interface AnalysisToolHandler {
        fun handle(apk: DecompiledApk, args: JsonObject): ToolResult
    }

    val analysisToolNames: Set<String> get() = analysisTools.keys
    val serverToolNames: Set<String> get() = serverTools.keys

    fun definitionFor(name: String): McpToolDef? = allDefinitions.find { it.name == name }

    fun executeAnalysis(name: String, apk: DecompiledApk, args: JsonObject): ToolResult {
        val handler = analysisTools[name]
            ?: return ToolResult.error(-32601, "Unknown analysis tool: $name")
        return try {
            handler.handle(apk, args)
        } catch (e: Exception) {
            ToolResult.internal("Tool execution error in $name: ${e.message}")
        }
    }

    fun executeServer(name: String, args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val handler = serverTools[name]
            ?: return ToolResult.error(-32601, "Unknown server tool: $name")
        return try {
            handler.handle(args, sessionId, state)
        } catch (e: Exception) {
            ToolResult.internal("Tool execution error in $name: ${e.message}")
        }
    }

    companion object {
        fun build(state: ServerState, transport: TransportMode): ToolRegistry {
            val serverHandlers = linkedMapOf<String, ServerToolHandler>()
            val analysisHandlers = linkedMapOf<String, AnalysisToolHandler>()
            val defs = mutableListOf<McpToolDef>()

            // ── Server tools ──
            for (def in ServerTools.definitions()) defs += def
            serverHandlers["upload_file"] = ServerToolHandler(ServerTools::uploadFile)
            if (transport == TransportMode.STDIO) {
                serverHandlers["register_file"] = ServerToolHandler(ServerTools::registerFile)
            }
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
            fun regAnalysis(list: List<McpToolDef>, vararg pairs: Pair<String, AnalysisToolHandler>) {
                defs += list
                for ((name, handler) in pairs) analysisHandlers[name] = handler
            }

            regAnalysis(CoreTools.definitions(),
                "decompile_apk" to AnalysisToolHandler(CoreTools::decompileApk),
                "survey" to AnalysisToolHandler(CoreTools::survey),
                "analysis_status" to AnalysisToolHandler(CoreTools::analysisStatus),
            )

            regAnalysis(ClassTools.definitions(),
                "list_classes" to AnalysisToolHandler(ClassTools::listClasses),
                "get_class_code" to AnalysisToolHandler(ClassTools::getClassCode),
                "class_info" to AnalysisToolHandler(ClassTools::classInfo),
            )

            regAnalysis(MethodTools.definitions(),
                "get_method_code" to AnalysisToolHandler(MethodTools::getMethodCode),
                "list_methods" to AnalysisToolHandler(MethodTools::listMethods),
            )

            regAnalysis(SearchTools.definitions(),
                "search_code" to AnalysisToolHandler(SearchTools::searchCode),
                "search_string" to AnalysisToolHandler(SearchTools::searchString),
                "find_class" to AnalysisToolHandler(SearchTools::findClass),
            )

            regAnalysis(ResourceTools.definitions(),
                "get_manifest" to AnalysisToolHandler(ResourceTools::getManifest),
                "get_resource" to AnalysisToolHandler(ResourceTools::getResource),
                "list_resources" to AnalysisToolHandler(ResourceTools::listResources),
            )

            regAnalysis(XrefTools.definitions(),
                "class_xrefs" to AnalysisToolHandler(XrefTools::classXrefs),
                "method_xrefs" to AnalysisToolHandler(XrefTools::methodXrefs),
            )

            return ToolRegistry(serverHandlers, analysisHandlers, defs)
        }
    }
}
