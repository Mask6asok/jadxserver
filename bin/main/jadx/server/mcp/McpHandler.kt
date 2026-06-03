package jadx.server.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jadx.server.engine.DecompiledApk
import jadx.server.engine.EngineOptions
import jadx.server.server.AcquireResult
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import jadx.server.util.getBoolean
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

class McpHandler(private val state: ServerState) {
    private val logger = LoggerFactory.getLogger(McpHandler::class.java)
    private val toolRegistry = ToolRegistry.build(state)

    fun createServer(): Server {
        val server = Server(
            Implementation(name = "jadx-server", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )
        registerAllTools(server)
        return server
    }

    private fun registerAllTools(server: Server) {
        for ((name, _) in toolRegistry.serverTools) {
            val def = toolRegistry.definitionFor(name) ?: continue
            val schema = buildToolSchema(def.params)
            val tool = Tool(def.name, schema, description = def.description)
            val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { conn, req ->
                val args = req.arguments ?: JsonObject(emptyMap())
                val sessionId = extractSessionId(req)
                val result = toolRegistry.executeServer(name, args, sessionId, state)
                toCallToolResult(result)
            }
            server.addTool(tool, handler)
        }

        for ((name, _) in toolRegistry.analysisTools) {
            val def = toolRegistry.definitionFor(name) ?: continue
            val allParams = listOf(
                ToolParam("file_hash", "string", "Short hash or MD5 prefix of the uploaded file", true)
            ) + def.params
            val schema = buildToolSchema(allParams)
            val tool = Tool(def.name, schema, description = def.description)
            val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { conn, req ->
                val args = req.arguments ?: JsonObject(emptyMap())
                val fileHash = args["file_hash"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing required parameter: file_hash")
                val background = args.getBoolean("background", false)
                val sessionId = extractSessionId(req)
                if (background) {
                    handleBackgroundAnalysis(name, fileHash, args, sessionId)
                } else {
                    handleSyncAnalysis(name, fileHash, args, sessionId)
                }
            }
            server.addTool(tool, handler)
        }

        logger.info("Registered {} server tools and {} analysis tools",
            toolRegistry.serverTools.size, toolRegistry.analysisTools.size)
    }

    private fun handleSyncAnalysis(
        toolName: String,
        fileHash: String,
        args: JsonObject,
        sessionId: String
    ): CallToolResult {
        val sourceDir = state.fileIndex.resolve(fileHash)?.let { entry ->
            state.config.uploadDir.resolve("binary").resolve(entry.md5).resolve("cache")
        }
        val engineOptions = EngineOptions(sourceDir = sourceDir)

        val acquireResult = acquireWithRetry(sessionId, fileHash, engineOptions)
            ?: return toCallToolResult(ToolResult.error(-32002, "All workers busy after retrying, try again later"))

        return when (acquireResult) {
            is AcquireResult.Found -> {
                try {
                    val apk = acquireResult.instance.state as DecompiledApk
                    toCallToolResult(toolRegistry.executeAnalysis(toolName, apk, args))
                } finally {
                    state.enginePool.release(acquireResult.instance)
                }
            }
            is AcquireResult.NeedSpawn -> {
                val instance = try {
                    state.engine.open(acquireResult.file, acquireResult.options)
                } catch (e: Exception) {
                    return toCallToolResult(ToolResult.internal("Failed to open decompiler: ${e.message}"))
                }
                state.enginePool.insert(sessionId, fileHash, instance)
                try {
                    val apk = instance.state as DecompiledApk
                    toCallToolResult(toolRegistry.executeAnalysis(toolName, apk, args))
                } finally {
                    state.enginePool.release(instance)
                }
            }
            AcquireResult.Busy -> toCallToolResult(ToolResult.error(-32002, "All workers busy, retry later"))
            AcquireResult.Full -> toCallToolResult(ToolResult.poolFull(state.config.maxInstances))
        }
    }

    private fun acquireWithRetry(
        sessionId: String,
        fileHash: String,
        options: EngineOptions,
        maxRetries: Int = 60,
        retryDelayMs: Long = 100
    ): AcquireResult? {
        for (i in 0 until maxRetries) {
            when (val result = state.enginePool.acquire(sessionId, fileHash, options)) {
                is AcquireResult.Found, is AcquireResult.NeedSpawn, is AcquireResult.Full -> return result
                is AcquireResult.Busy -> {
                    try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { return null }
                }
            }
        }
        return null
    }

    private fun handleBackgroundAnalysis(
        toolName: String,
        fileHash: String,
        args: JsonObject,
        sessionId: String
    ): CallToolResult {
        val taskId = state.taskManager.create(toolName)
        Thread.ofVirtual().name("task-$taskId").start {
            try {
                handleSyncAnalysis(toolName, fileHash, args, sessionId)
                state.taskManager.complete(taskId, buildJsonObject {
                    put("status", JsonPrimitive("completed"))
                })
            } catch (e: Exception) {
                state.taskManager.fail(taskId, e.message ?: "Unknown error")
            }
        }
        return toCallToolResult(ToolResult.success {
            put("task_id", JsonPrimitive(taskId))
            put("status", JsonPrimitive("running"))
        })
    }

    private fun extractSessionId(request: CallToolRequest): String = "default"

    private fun buildToolSchema(params: List<ToolParam>): ToolSchema {
        val properties = buildJsonObject {
            for (p in params) {
                put(p.name, buildJsonObject {
                    put("type", JsonPrimitive(p.type))
                    put("description", JsonPrimitive(p.description))
                })
            }
        }
        val required = params.filter { it.required }.map { it.name }
        return ToolSchema("http://json-schema.org/draft-07/schema#", properties, required, buildJsonObject { })
    }

    companion object {
        fun toCallToolResult(result: ToolResult): CallToolResult = when (result) {
            is ToolResult.Success -> CallToolResult(
                content = listOf(TextContent(result.data.toString(), null, buildJsonObject { })),
                isError = false,
                structuredContent = null,
                meta = buildJsonObject { }
            )
            is ToolResult.Error -> CallToolResult(
                content = listOf(TextContent(result.message, null, buildJsonObject { })),
                isError = true,
                structuredContent = null,
                meta = buildJsonObject { }
            )
        }
    }
}
