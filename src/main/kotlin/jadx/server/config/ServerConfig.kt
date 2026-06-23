package jadx.server.config

import java.nio.file.Path
import java.time.Duration

data class ServerConfig(
    val listen: String = "127.0.0.1:8080",
    val publicBaseUrl: String? = null,
    val authorizationToken: String? = null,
    val maxInstances: Int = 0,  // 0 = auto (min(CPU/4, 2))
    val maxPerFile: Int = 4,
    val idleTimeout: Duration = Duration.ofMinutes(5),
    val cleanupInterval: Duration = Duration.ofSeconds(10),
    val maxCachedApks: Int = -1,  // -1 = no limit
    val uploadDir: Path = Path.of("./uploads"),
    val toolTimeout: Duration = Duration.ofMinutes(5),
    val xrefMode: XrefMode = XrefMode.JADX,
)

enum class XrefMode { TEXT, JADX }
