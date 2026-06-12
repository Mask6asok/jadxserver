package jadx.server.tools

import jadx.server.mcp.McpToolDef
import jadx.server.mcp.ToolResult
import jadx.server.config.TransportMode
import jadx.server.project.JadxProjectService
import jadx.server.server.FileStatus
import jadx.server.server.InstanceInfo
import jadx.server.server.ServerState
import jadx.server.server.TaskStatus
import jadx.server.util.getBoolean
import jadx.server.util.getInt
import jadx.server.util.getString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

object ServerTools {
    private val logger = LoggerFactory.getLogger(ServerTools::class.java)
    private val projectService = JadxProjectService()

    fun definitions(): List<McpToolDef> = listOf(
        McpToolDef("upload_file", "Get upload URL and instructions for uploading a binary file"),
        McpToolDef("register_file", "Register a file that was copied to the upload directory (stdio mode)")
            .param("file_path", "string", "Absolute path to the copied file", true),
        McpToolDef("save_project", "Generate or refresh upstream-compatible project.jadx for a file")
            .param("file_hash", "string", "Short hash or MD5 prefix of the uploaded file", true),
        McpToolDef("list_files", "List known binaries (uploaded or previously opened)")
            .param("name", "string", "Filter by basename (substring match)", false)
            .param("md5", "string", "Filter by MD5 hash (exact match)", false)
            .param("type", "string", "Filter by file type extension (e.g. .apk)", false),
        McpToolDef("list_instances", "List all active engine instances in the pool"),
        McpToolDef("server_health", "Check server health status including uptime, memory, and instance counts"),
        McpToolDef("tool_catalog", "Search and discover available tools by keyword")
            .param("query", "string", "Optional keyword to search in tool names and descriptions", false),
        McpToolDef("tool_help", "Get detailed help for a specific tool by exact name")
            .param("tool", "string", "Exact tool name to get help for", true),
        McpToolDef("task_status", "Check the status and result of a background task")
            .param("task_id", "string", "Task ID returned by a background tool call", true),
        McpToolDef("wait_for_analysis", "Wait for a file's background decompilation to complete")
            .param("file_hash", "string", "Short hash or MD5 prefix of the file", true)
            .param("timeout_secs", "number", "Maximum seconds to wait (default: 30)", false),
        McpToolDef("cleanup_session_workers", "Close idle engine instances for the current session")
            .param("force", "boolean", "Also close busy instances (default: false)", false)
    )

    fun uploadFile(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        return when (state.config.transport) {
            TransportMode.HTTP -> {
                val baseUrl = state.config.publicBaseUrl?.trimEnd('/') ?: "http://${state.config.listen}"
                ToolResult.success {
                    put("upload_url", JsonPrimitive("$baseUrl/upload"))
                    put("upload_method", JsonPrimitive("POST"))
                    put("content_type", JsonPrimitive("multipart/form-data"))
                    put("field_name", JsonPrimitive("file"))
                    put("description", JsonPrimitive("Upload an APK/DEX/JAR file via multipart POST to $baseUrl/upload. Response includes file_hash for subsequent tools."))
                }
            }
            TransportMode.STDIO -> {
                val uploadDir = state.config.uploadDir.toAbsolutePath().normalize().toString()
                ToolResult.success {
                    put("mode", JsonPrimitive("local_copy"))
                    put("target_dir", JsonPrimitive(uploadDir))
                    put("description", JsonPrimitive("Copy an APK/DEX/JAR file to '$uploadDir', then call register_file with the copied file path to complete the upload."))
                }
            }
        }
    }

    fun registerFile(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val filePath = args.getString("file_path")
            ?: return ToolResult.badParams("Missing required parameter: file_path")

        val path = Path.of(filePath)
        if (!Files.exists(path)) {
            return ToolResult.notFound("File not found: $filePath")
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.badParams("Path is not a regular file: $filePath")
        }

        val entry = state.fileIndex.add(path, state.config.uploadDir)
        return ToolResult.success {
            put("file_hash", JsonPrimitive(entry.hash))
            put("md5", JsonPrimitive(entry.md5))
            put("name", JsonPrimitive(entry.originalName))
            put("size", JsonPrimitive(entry.fileSize))
            put("status", JsonPrimitive("registered"))
        }
    }

    fun listFiles(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val nameFilter = args.getString("name")
        val md5Filter = args.getString("md5")
        val typeFilter = args.getString("type")

        val entries = state.fileIndex.list(nameFilter = nameFilter, typeFilter = typeFilter, md5Filter = md5Filter)
        return ToolResult.success {
            put("count", JsonPrimitive(entries.size))
            put("files", buildJsonArray {
                for (e in entries) {
                    add(buildJsonObject {
                        put("file_hash", JsonPrimitive(e.hash))
                        put("md5", JsonPrimitive(e.md5))
                        put("name", JsonPrimitive(e.originalName))
                        put("path", JsonPrimitive(e.path))
                        put("size", JsonPrimitive(e.fileSize))
                        put("stored_at", JsonPrimitive(e.storedAt))
                        put("status", JsonPrimitive(e.status.name))
                    })
                }
            })
        }
    }

    fun saveProject(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val fileHash = args.getString("file_hash")
            ?: return ToolResult.badParams("Missing required parameter: file_hash")
        val entry = state.fileIndex.resolve(fileHash)
            ?: return ToolResult.notFound("File not found: $fileHash")

        val binaryPath = Path.of(entry.path)
        val binaryDir = binaryPath.parent
        val projectFile = binaryDir.resolve("project.jadx")
        val cacheDir = binaryDir.resolve("project.cache")

        val project = projectService.createDefault(binaryPath, cacheDir, projectFile)
        projectService.save(projectFile, project)
        state.fileIndex.updateProjectPaths(entry.hash, projectFile, cacheDir)

        return ToolResult.success {
            put("file_hash", JsonPrimitive(entry.hash))
            put("project_file", JsonPrimitive(projectFile.toAbsolutePath().normalize().toString()))
            put("cache_dir", JsonPrimitive(cacheDir.toAbsolutePath().normalize().toString()))
            put("status", JsonPrimitive("saved"))
        }
    }

    fun listInstances(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val instances = state.enginePool.listInstances()
        return ToolResult.success {
            put("count", JsonPrimitive(instances.size))
            put("instances", buildJsonArray {
                for (info in instances) {
                    add(buildJsonObject {
                        put("instance_id", JsonPrimitive(info.instanceId))
                        put("file_hash", JsonPrimitive(info.fileHash))
                        put("session_id", JsonPrimitive(info.sessionId))
                        put("state", JsonPrimitive(info.state.name))
                        put("opened_at", JsonPrimitive(info.openedAt.toString()))
                    })
                }
            })
        }
    }

    fun serverHealth(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val runtime = Runtime.getRuntime()
        val uptime = Duration.between(state.startedAt, java.time.Instant.now())
        return ToolResult.success {
            put("status", JsonPrimitive("healthy"))
            put("uptime_seconds", JsonPrimitive(uptime.seconds))
            put("instance_count", JsonPrimitive(state.enginePool.instanceCount()))
            put("session_count", JsonPrimitive(state.sessionManager.sessionCount()))
            put("file_count", JsonPrimitive(state.fileIndex.entryCount()))
            put("task_count", JsonPrimitive(state.taskManager.taskCount()))
            put("memory", buildJsonObject {
                put("max_mb", JsonPrimitive(runtime.maxMemory() / (1024 * 1024)))
                put("total_mb", JsonPrimitive(runtime.totalMemory() / (1024 * 1024)))
                put("free_mb", JsonPrimitive(runtime.freeMemory() / (1024 * 1024)))
                put("used_mb", JsonPrimitive((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)))
            })
        }
    }

    fun toolCatalog(args: JsonObject, sessionId: String, state: ServerState, allDefs: List<McpToolDef>): ToolResult {
        val query = args.getString("query")?.lowercase()
        val filtered = if (query != null) {
            allDefs.filter { it.name.contains(query) || it.description.lowercase().contains(query) }
        } else {
            allDefs
        }
        return ToolResult.success {
            put("count", JsonPrimitive(filtered.size))
            put("tools", buildJsonArray {
                for (def in filtered) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(def.name))
                        put("description", JsonPrimitive(def.description))
                        put("param_count", JsonPrimitive(def.params.size))
                        put("category", JsonPrimitive(if (state.enginePool.listInstances().isNotEmpty()) "available" else "available"))
                    })
                }
            })
        }
    }

    fun toolHelp(args: JsonObject, allDefs: List<McpToolDef>): ToolResult {
        val toolName = args.getString("tool")
            ?: return ToolResult.badParams("Missing required parameter: tool")
        val def = allDefs.find { it.name == toolName }
            ?: return ToolResult.notFound("Unknown tool: $toolName")
        return ToolResult.success {
            put("name", JsonPrimitive(def.name))
            put("description", JsonPrimitive(def.description))
            put("parameters", buildJsonArray {
                for (p in def.params) {
                    add(buildJsonObject {
                        put("name", JsonPrimitive(p.name))
                        put("type", JsonPrimitive(p.type))
                        put("description", JsonPrimitive(p.description))
                        put("required", JsonPrimitive(p.required))
                    })
                }
            })
        }
    }

    fun taskStatus(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val taskId = args.getString("task_id")
            ?: return ToolResult.badParams("Missing required parameter: task_id")
        val task = state.taskManager.get(taskId)
            ?: return ToolResult.notFound("Unknown task: $taskId")
        return ToolResult.success {
            put("task_id", JsonPrimitive(task.taskId))
            put("tool_name", JsonPrimitive(task.toolName))
            put("status", JsonPrimitive(task.status.name))
            put("progress", JsonPrimitive(task.progress))
            put("created_at", JsonPrimitive(task.createdAt.toString()))
            task.completedAt?.let { put("completed_at", JsonPrimitive(it.toString())) }
            task.result?.let { put("result", it) }
            task.error?.let { put("error", JsonPrimitive(it)) }
            task.result?.let { result ->
                result["error_code"]?.let { put("error_code", it) }
                result["error_reason"]?.let { put("error_reason", it) }
                result["error_message"]?.let { put("error_message", it) }
            }
            task.error?.let { err ->
                put("error_code", JsonPrimitive("FAILED"))
                put("error_reason", JsonPrimitive("task_failed"))
                put("error_message", JsonPrimitive(err))
            }
        }
    }

    fun waitForAnalysis(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val fileHash = args.getString("file_hash")
            ?: return ToolResult.badParams("Missing required parameter: file_hash")
        val timeoutSecs = args.getInt("timeout_secs", 30)

        val entry = state.fileIndex.resolve(fileHash)
            ?: return ToolResult.notFound("File not found: $fileHash")

        if (entry.status != FileStatus.UPLOADED && entry.status != FileStatus.ANALYZING) {
            return ToolResult.success {
                put("file_hash", JsonPrimitive(entry.hash))
                put("status", JsonPrimitive(entry.status.name))
                put("ready", JsonPrimitive(entry.status == FileStatus.ANALYZED))
                if (entry.status == FileStatus.FAILED) {
                    put("error_code", JsonPrimitive("FAILED"))
                    put("error_reason", JsonPrimitive("analysis_failed"))
                    put("error_message", JsonPrimitive("Previous analysis attempt failed"))
                }
            }
        }

        val deadline = System.currentTimeMillis() + (timeoutSecs * 1000L)
        while (true) {
            val currentEntry = state.fileIndex.resolve(fileHash)
                ?: return ToolResult.notFound("File not found during analysis: $fileHash")
            if (currentEntry.status != FileStatus.UPLOADED && currentEntry.status != FileStatus.ANALYZING) {
                return ToolResult.success {
                    put("file_hash", JsonPrimitive(currentEntry.hash))
                    put("status", JsonPrimitive(currentEntry.status.name))
                    put("ready", JsonPrimitive(currentEntry.status == FileStatus.ANALYZED))
                    if (currentEntry.status == FileStatus.FAILED) {
                        put("error_code", JsonPrimitive("FAILED"))
                        put("error_reason", JsonPrimitive("analysis_failed"))
                        put("error_message", JsonPrimitive("Previous analysis attempt failed"))
                    }
                }
            }
            if (System.currentTimeMillis() > deadline) {
                return ToolResult.success {
                    put("file_hash", JsonPrimitive(currentEntry.hash))
                    put("status", JsonPrimitive(currentEntry.status.name))
                    put("ready", JsonPrimitive(false))
                    put("error_code", JsonPrimitive("TIMEOUT"))
                    put("error_reason", JsonPrimitive("analysis_timeout"))
                    put("error_message", JsonPrimitive("Timeout waiting for analysis after ${timeoutSecs}s — file still ${currentEntry.status.name}"))
                }
            }
            Thread.sleep(500)
        }
    }

    fun cleanupSessionWorkers(args: JsonObject, sessionId: String, state: ServerState): ToolResult {
        val force = args.getBoolean("force", false)
        val instances = state.enginePool.listInstances()
        val sessionInstances = instances.filter { it.sessionId == sessionId }
        var closedCount = 0

        for (info in sessionInstances) {
            if (force || info.state.name == "Idle") {
                val discarded = state.enginePool.discard(
                    jadx.server.engine.EngineInstance(
                        instanceId = info.instanceId,
                        engineName = "",
                        fileHash = info.fileHash,
                        state = Unit
                    )
                )
                if (discarded != null) {
                    state.engine.close(discarded)
                    state.sessionManager.removeInstance(sessionId, info.instanceId)
                    closedCount++
                }
            }
        }

        return ToolResult.success {
            put("closed_count", JsonPrimitive(closedCount))
            put("remaining", JsonPrimitive(sessionInstances.size - closedCount))
        }
    }
}
