package jadx.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val BEARER_REALM = "jadx-server"
private val PROTECTED_HTTP_PATHS = setOf("/mcp", "/upload")
private val BEARER_HEADER = Regex("^Bearer[\\t ]+([^\\s]+)$", RegexOption.IGNORE_CASE)

/**
 * Protects the HTTP MCP and binary upload endpoints when a token is configured.
 * An absent or blank configured token intentionally preserves unauthenticated mode.
 */
fun Application.installOptionalBearerAuthorization(configuredToken: String?) {
    val expectedToken = configuredToken?.takeIf { it.isNotBlank() } ?: return
    val expectedBytes = expectedToken.toByteArray(StandardCharsets.UTF_8)

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val isProtected = PROTECTED_HTTP_PATHS.any { path == it || path.startsWith("$it/") }
        if (!isProtected || call.request.local.method == HttpMethod.Options) {
            return@intercept
        }

        val suppliedToken = call.request.header(HttpHeaders.Authorization)
            ?.let { BEARER_HEADER.matchEntire(it)?.groupValues?.get(1) }
        val authorized = suppliedToken != null && MessageDigest.isEqual(
            suppliedToken.toByteArray(StandardCharsets.UTF_8),
            expectedBytes,
        )
        if (!authorized) {
            call.response.headers.append(
                HttpHeaders.WWWAuthenticate,
                "Bearer realm=\"$BEARER_REALM\"",
            )
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            finish()
        }
    }
}
