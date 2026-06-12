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
import jadx.server.server.FileStatus
import jadx.server.server.ServerState
import jadx.server.config.TransportMode
import jadx.server.tools.CoreTools
import jadx.server.project.JadxProjectService
import jadx.server.tools.ToolRegistry
import jadx.server.util.getBoolean
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class McpHandler(private val state: ServerState) {
    private val logger = LoggerFactory.getLogger(McpHandler::class.java)
    private val toolRegistry = ToolRegistry.build(state, state.config.transport)
    private val projectService = JadxProjectService()

    fun createServer(): Server {
        val server = Server(
            Implementation(name = "jadx-server", version = "0.1.3"),
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
                val sessionId = extractSessionId(conn)
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
                val sessionId = extractSessionId(conn)
                if (name == "analysis_status") {
                    toCallToolResult(CoreTools.analysisStatus(state, fileHash))
                } else if (background) {
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
        val entry = state.fileIndex.resolve(fileHash)
            ?: return toCallToolResult(ToolResult.notFound("File not found: $fileHash"))

        val projectFile = entry.projectFilePath?.let { Path.of(it) }
        val binaryDir = Path.of(entry.path).parent
        val defaultProjectFile = binaryDir.resolve("project.jadx")
        val defaultCacheDir = binaryDir.resolve("project.cache")
        val defaultCodeCacheDir = defaultCacheDir.resolve("code")
        val cleanupContext = FailedRunCleanupContext(
            hash = entry.hash,
            originalProjectFilePath = entry.projectFilePath,
            originalCacheDirPath = entry.cacheDirPath,
            projectFilePath = projectFile ?: defaultProjectFile,
            cacheDirPath = entry.cacheDirPath?.let { Path.of(it) } ?: defaultCacheDir,
            generatedCodeCacheDirPath = defaultCodeCacheDir,
            projectFileExistedAtStart = projectFile?.let { Files.exists(it) } ?: Files.exists(defaultProjectFile),
            cacheDirExistedAtStart = entry.cacheDirPath?.let { Files.exists(Path.of(it)) } ?: Files.exists(defaultCacheDir),
            generatedCodeCacheDirExistedAtStart = Files.exists(defaultCodeCacheDir)
        )

        state.fileIndex.updateStatus(entry.hash, FileStatus.ANALYZING)

        val resolvedProject = if (projectFile != null && Files.exists(projectFile)) {
            try {
                projectService.load(projectFile)
            } catch (e: Exception) {
                state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                cleanupFailedRunArtifacts(cleanupContext)
                return buildFailureResult(
                    code = "INTERNAL",
                    reason = "project_load_failed",
                    message = "Failed to load project file: ${e.message}"
                )
            }
        } else {
            null
        }

        val sourceDir = when {
            toolName == "get_manifest" -> null
            resolvedProject != null -> projectService.resolveProjectCacheDir(projectFile!!, resolvedProject)?.resolve("code")
            entry.cacheDirPath != null -> Path.of(entry.cacheDirPath!!).resolve("code")
            else -> state.config.uploadDir.resolve("binary").resolve(entry.md5).resolve("project.cache").resolve("code")
        }

        val inputFiles = if (resolvedProject != null) {
            listOf(projectService.resolveProjectBinary(projectFile!!, resolvedProject))
        } else {
            null
        }

        val engineOptions = EngineOptions(
            sourceDir = sourceDir,
            projectFile = projectFile,
            inputFiles = inputFiles,
            pluginOptions = resolvedProject?.pluginOptions ?: emptyMap(),
            xrefMode = state.config.xrefMode
        )

        return when (val retryOutcome = acquireWithRetry(sessionId, fileHash, engineOptions)) {
            is AcquireRetryResult.Success -> {
                when (val acquireResult = retryOutcome.result) {
                    is AcquireResult.Found -> {
                        try {
                            val apk = acquireResult.instance.state as DecompiledApk
                            val result = runBlocking {
                                withTimeout(state.config.toolTimeout.toMillis()) {
                                    toCallToolResult(toolRegistry.executeAnalysis(toolName, apk, args))
                                }
                            }
                            state.fileIndex.updateStatus(entry.hash, FileStatus.ANALYZED)
                            result
                        } catch (e: TimeoutCancellationException) {
                            state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                            cleanupFailedRunArtifacts(cleanupContext)
                            buildFailureResult(
                                code = "TIMEOUT",
                                reason = "analysis_timeout",
                                message = "Tool execution timed out"
                            )
                        } catch (e: Exception) {
                            state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                            cleanupFailedRunArtifacts(cleanupContext)
                            buildFailureResult(
                                code = "INTERNAL",
                                reason = "analysis_failed",
                                message = "Tool execution failed: ${e.message}",
                                legacyCode = -32603
                            )
                        } finally {
                            state.enginePool.release(acquireResult.instance)
                        }
                    }
                    is AcquireResult.NeedSpawn -> {
                        val instance = try {
                            state.engine.open(acquireResult.file, acquireResult.options)
                        } catch (e: Exception) {
                            state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                            cleanupFailedRunArtifacts(cleanupContext)
                            return buildFailureResult(
                                code = "INTERNAL",
                                reason = "engine_open_failed",
                                message = "Failed to open decompiler: ${e.message}"
                            )
                        }
                        if (projectFile == null && sourceDir != null) {
                            val generatedProjectFile = binaryDir.resolve("project.jadx")
                            val generatedCacheDir = binaryDir.resolve("project.cache")
                            try {
                                val project = projectService.createDefault(Path.of(entry.path), generatedCacheDir, generatedProjectFile)
                                projectService.save(generatedProjectFile, project)
                                state.fileIndex.updateProjectPaths(entry.hash, generatedProjectFile, generatedCacheDir)
                            } catch (e: Exception) {
                                logger.warn("Failed to save project file for {}: {}", entry.hash, e.message)
                            }
                        }
                        state.enginePool.insert(sessionId, fileHash, instance)
                        try {
                            val apk = instance.state as DecompiledApk
                            val result = runBlocking {
                                withTimeout(state.config.toolTimeout.toMillis()) {
                                    toCallToolResult(toolRegistry.executeAnalysis(toolName, apk, args))
                                }
                            }
                            state.fileIndex.updateStatus(entry.hash, FileStatus.ANALYZED)
                            result
                        } catch (e: TimeoutCancellationException) {
                            state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                            cleanupFailedRunArtifacts(cleanupContext)
                            buildFailureResult(
                                code = "TIMEOUT",
                                reason = "analysis_timeout",
                                message = "Tool execution timed out"
                            )
                        } catch (e: Exception) {
                            state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                            cleanupFailedRunArtifacts(cleanupContext)
                            buildFailureResult(
                                code = "INTERNAL",
                                reason = "analysis_failed",
                                message = "Tool execution failed: ${e.message}",
                                legacyCode = -32603
                            )
                        } finally {
                            state.enginePool.release(instance)
                        }
                    }
                    AcquireResult.Busy -> {
                        state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                        cleanupFailedRunArtifacts(cleanupContext)
                        buildFailureResult(
                            code = "WORKER_EXHAUSTED",
                            reason = "worker_exhausted",
                            message = "All workers busy, retry later",
                            legacyCode = -32002
                        )
                    }
                    AcquireResult.Full -> {
                        state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                        cleanupFailedRunArtifacts(cleanupContext)
                        buildFailureResult(code = "POOL_FULL", reason = "pool_full", message = "Worker pool at capacity (${state.config.maxInstances} instances)", legacyCode = -32003)
                    }
                }
            }
            is AcquireRetryResult.Interrupted -> {
                state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                cleanupFailedRunArtifacts(cleanupContext)
                buildFailureResult(
                    code = "WORKER_INTERRUPTED",
                    reason = "worker_interrupted",
                    message = "Acquire was interrupted"
                )
            }
            AcquireRetryResult.Exhausted -> {
                state.fileIndex.updateStatus(entry.hash, FileStatus.FAILED)
                cleanupFailedRunArtifacts(cleanupContext)
                buildFailureResult(
                    code = "WORKER_EXHAUSTED",
                    reason = "worker_exhausted",
                    message = "All workers busy after retrying, try again later",
                    legacyCode = -32002
                )
            }
        }
    }

    internal fun cleanupFailedRunArtifacts(context: FailedRunCleanupContext) {
        clearProjectPaths(context.hash)

        if (!context.projectFileExistedAtStart) {
            deletePathIfExists(context.projectFilePath)
        }
        if (!context.cacheDirExistedAtStart) {
            deleteRecursivelyIfExists(context.cacheDirPath)
        }
        val codeDirCoveredByCacheDir = context.generatedCodeCacheDirPath.startsWith(context.cacheDirPath)
        if (!context.generatedCodeCacheDirExistedAtStart && (!codeDirCoveredByCacheDir || context.cacheDirExistedAtStart)) {
            deleteRecursivelyIfExists(context.generatedCodeCacheDirPath)
        }
    }

    private fun clearProjectPaths(hash: String) {
        state.fileIndex.updateProjectPaths(hash, null, null)
    }

    private fun deletePathIfExists(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: Exception) {
            logger.warn("Failed to delete generated file {} after analysis failure: {}", path, e.message)
        }
    }

    private fun deleteRecursivelyIfExists(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { current -> Files.deleteIfExists(current) }
        } catch (e: Exception) {
            logger.warn("Failed to delete generated directory {} after analysis failure: {}", path, e.message)
        }
    }

    private fun buildFailureResult(
        code: String,
        reason: String,
        message: String,
        legacyCode: Int = -32603
    ): CallToolResult {
        return toCallToolResult(ToolResult.error(legacyCode, buildJsonObject {
            put("error_code", JsonPrimitive(code))
            put("error_reason", JsonPrimitive(reason))
            put("error_message", JsonPrimitive(message))
        }.toString()))
    }

    private sealed class AcquireRetryResult {
        data class Success(val result: AcquireResult) : AcquireRetryResult()
        data object Interrupted : AcquireRetryResult()
        data object Exhausted : AcquireRetryResult()
    }

    private fun acquireWithRetry(
        sessionId: String,
        fileHash: String,
        options: EngineOptions,
        maxRetries: Int = 60,
        retryDelayMs: Long = 100
    ): AcquireRetryResult {
        for (i in 0 until maxRetries) {
            when (val result = state.enginePool.acquire(sessionId, fileHash, options)) {
                is AcquireResult.Found, is AcquireResult.NeedSpawn, is AcquireResult.Full ->
                    return AcquireRetryResult.Success(result)
                is AcquireResult.Busy -> {
                    try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) {
                        return AcquireRetryResult.Interrupted
                    }
                }
            }
        }
        return AcquireRetryResult.Exhausted
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
                val result = handleSyncAnalysis(toolName, fileHash, args, sessionId)
                val contentText = (result.content.firstOrNull() as? TextContent)?.text
                val isError = result.isError == true || contentText?.let {
                    try {
                        Json.parseToJsonElement(it).jsonObject["error_code"] != null
                    } catch (_: Exception) {
                        false
                    }
                } ?: false
                if (isError) {
                    state.taskManager.fail(taskId, contentText ?: "Unknown error")
                } else {
                    val actualResult = extractTaskResult(result)
                    state.taskManager.complete(taskId, actualResult)
                }
            } catch (e: Exception) {
                state.taskManager.fail(taskId, e.message ?: "Unknown error")
            }
        }
        return toCallToolResult(ToolResult.success {
            put("task_id", JsonPrimitive(taskId))
            put("status", JsonPrimitive("running"))
        })
    }

    private fun extractTaskResult(result: CallToolResult): JsonObject {
        val text = (result.content.firstOrNull() as? TextContent)?.text
        return if (text != null) {
            try {
                Json.parseToJsonElement(text) as? JsonObject
                    ?: buildJsonObject { put("raw_text", JsonPrimitive(text)) }
            } catch (e: Exception) {
                logger.warn("Failed to parse background task result as JSON: {}", e.message)
                buildJsonObject {
                    put("status", JsonPrimitive("completed"))
                    put("raw_text", JsonPrimitive(text))
                }
            }
        } else {
            buildJsonObject { put("status", JsonPrimitive("completed")) }
        }
    }

    private fun extractSessionId(conn: ClientConnection): String {
        return try { conn.sessionId } catch (_: Exception) { "default" }
    }

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

internal data class FailedRunCleanupContext(
    val hash: String,
    val originalProjectFilePath: String?,
    val originalCacheDirPath: String?,
    val projectFilePath: Path,
    val cacheDirPath: Path,
    val generatedCodeCacheDirPath: Path,
    val projectFileExistedAtStart: Boolean,
    val cacheDirExistedAtStart: Boolean,
    val generatedCodeCacheDirExistedAtStart: Boolean
)
