package jadx.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import jadx.server.config.ServerConfig
import jadx.server.config.TransportMode
import jadx.server.config.XrefMode
import jadx.server.mcp.McpHandler
import jadx.server.server.ServerState
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

fun main(args: Array<String>) {
    var transportMode = TransportMode.HTTP
    var listenAddr = "127.0.0.1:8080"
    var publicBaseUrl: String? = null
    var authorizationToken = System.getenv("JADX_SERVER_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
    var maxInst = 0
    var upload = "./uploads"
    var xrefMode = XrefMode.JADX
    var maxPerFile = 4
    var idleTimeout = Duration.ofMinutes(5)
    var cleanupInterval = Duration.ofSeconds(10)
    var maxCachedApks = -1
    var toolTimeout = Duration.ofMinutes(5)
    var jadxVerbose = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--stdio" -> transportMode = TransportMode.STDIO
            "--transport" -> { if (i+1 < args.size && args[i+1] == "stdio") transportMode = TransportMode.STDIO; i++ }
            "--listen" -> { if (i+1 < args.size) listenAddr = args[i+1]; i++ }
            "--public-base-url" -> { if (i+1 < args.size) publicBaseUrl = args[i+1]; i++ }
            "--auth-token" -> { if (i+1 < args.size) authorizationToken = args[i+1].takeIf { it.isNotBlank() }; i++ }
            "--max-instances", "-m" -> { if (i+1 < args.size) maxInst = args[i+1].toIntOrNull() ?: 0; i++ }
            "--upload-dir" -> { if (i+1 < args.size) upload = args[i+1]; i++ }
            "--xref-mode" -> { if (i+1 < args.size) xrefMode = XrefMode.valueOf(args[i+1].uppercase()); i++ }
            "--max-per-file" -> { if (i+1 < args.size) maxPerFile = args[i+1].toIntOrNull() ?: 4; i++ }
            "--idle-timeout" -> { if (i+1 < args.size) idleTimeout = Duration.ofSeconds(args[i+1].toLongOrNull() ?: 300); i++ }
            "--cleanup-interval" -> { if (i+1 < args.size) cleanupInterval = Duration.ofSeconds(args[i+1].toLongOrNull() ?: 10); i++ }
            "--max-cached-apks" -> { if (i+1 < args.size) maxCachedApks = args[i+1].toIntOrNull() ?: 10; i++ }
            "--tool-timeout" -> { if (i+1 < args.size) toolTimeout = Duration.ofSeconds(args[i+1].toLongOrNull() ?: 300); i++ }
            "--jadx-verbose" -> jadxVerbose = true
            "--help", "-h" -> { printHelp(); return }
        }
        i++
    }

    if (jadxVerbose) {
        val jadxLogger = LoggerFactory.getLogger("jadx") as LogbackLogger
        jadxLogger.level = Level.WARN
        LoggerFactory.getLogger("jadx.server").info("jadx verbose logging enabled (level=WARN)")
    }

    val config = ServerConfig(
        transport = transportMode,
        listen = listenAddr,
        publicBaseUrl = publicBaseUrl,
        authorizationToken = authorizationToken,
        maxInstances = maxInst,
        maxPerFile = maxPerFile,
        idleTimeout = idleTimeout,
        cleanupInterval = cleanupInterval,
        maxCachedApks = maxCachedApks,
        uploadDir = Path.of(upload),
        toolTimeout = toolTimeout,
        xrefMode = xrefMode,
    )
    val state = ServerState(config)
    val handler = McpHandler(state)
    val server = handler.createServer()

    Runtime.getRuntime().addShutdownHook(Thread {
        LoggerFactory.getLogger("jadx.server").info("Shutting down...")
        runBlocking { server.close() }
        state.shutdown()
    })

    when (config.transport) {
        TransportMode.STDIO -> {
            val transport = StdioServerTransport(
                System.`in`.asSource().buffered(),
                System.out.asSink().buffered()
            )
            runBlocking {
                server.createSession(transport)
                transport.start()
            }
        }
        TransportMode.HTTP -> startHttpServer(config, state)
    }
}

private const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024 // 500MB

fun startHttpServer(config: ServerConfig, state: ServerState) {
    val logger = LoggerFactory.getLogger("jadx.server.HttpServer")
    val parts = config.listen.split(":")
    val host = parts.getOrElse(0) { "127.0.0.1" }
    val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
    logger.info("HTTP bearer authentication is {}", if (config.authorizationToken.isNullOrBlank()) "disabled" else "enabled")

    embeddedServer(Netty, host = host, port = port) {
        installHttpCors()
        installOptionalBearerAuthorization(config.authorizationToken)
        val handler = McpHandler(state)
        val server = handler.createServer()
        this.mcpStreamableHttp { server }
        routing {
            post("/upload") {
                val multipart = call.receiveMultipart(MAX_UPLOAD_BYTES)
                var fileEntry: jadx.server.server.FileEntry? = null
                while (true) {
                    val part = multipart.readPart() ?: break
                    when (part) {
                        is PartData.FileItem -> {
                            val fileName = part.originalFileName ?: "unknown.apk"
                            val tmpDir = state.config.uploadDir.resolve("tmp")
                            Files.createDirectories(tmpDir)
                            val tmpFile = tmpDir.resolve(fileName)
                            part.streamProvider().use { input ->
                                Files.copy(input, tmpFile, StandardCopyOption.REPLACE_EXISTING)
                            }
                            fileEntry = state.fileIndex.add(tmpFile, state.config.uploadDir)
                        }
                        else -> part.dispose()
                    }
                }
                val entry = fileEntry
                if (entry != null) {
                    call.respond(buildJsonObject {
                        put("file_hash", JsonPrimitive(entry.hash))
                        put("md5", JsonPrimitive(entry.md5))
                        put("name", JsonPrimitive(entry.originalName))
                        put("size", JsonPrimitive(entry.fileSize))
                    })
                } else {
                    call.respond(HttpStatusCode.BadRequest, "No file uploaded")
                }
            }
        }
    }.start(wait = true)
}

fun Application.installHttpCors() {
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("mcp-session-id")
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Delete)
    }
}

fun printHelp() {
    println("""
        jadx-server — MCP server for Android APK decompilation

        Usage: jadx-server [options]

        Options:
          --stdio                    Use stdio transport (default: HTTP)
          --transport <mode>         Transport mode: http, stdio (default: http)
          --listen <host:port>       HTTP listen address (default: 127.0.0.1:8080)
          --public-base-url <url>    Public base URL used in upload_file responses
          --auth-token <token>       Require this Bearer token for /mcp and /upload
          --max-instances, -m <n>    Max engine instances, 0=auto(min CPU/4, lower-bounded to 1, upper-bounded to 2)
          --max-per-file <n>         Max concurrent instances per file (default: 4)
          --idle-timeout <s>         Idle engine eviction timeout in seconds (default: 300)
          --cleanup-interval <s>     Eviction check interval in seconds (default: 10)
          --max-cached-apks <n>      Max cached APK entries (default: 10)
          --upload-dir <path>        Upload directory (default: ./uploads)
          --tool-timeout <s>         Tool execution timeout in seconds (default: 300)
          --xref-mode <mode>         Cross-reference mode: text, jadx (default: jadx)
          --jadx-verbose             Enable jadx.core library logging (default: OFF)
          --help, -h                 Show this help

        Examples:
          jadx-server                                          # default HTTP on :8080
          jadx-server --xref-mode jadx                         # precise bytecode xref
          jadx-server --listen 0.0.0.0:9090 --max-instances 4  # public, 4 workers
          jadx-server --stdio                                  # MCP stdio mode
    """.trimIndent())
}
