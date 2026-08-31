package jadx.server.config

import java.nio.file.Path
import java.time.Duration

data class ServerConfig(
    val listen: String = "127.0.0.1:8080",
    val publicBaseUrl: String? = null,
    val authorizationToken: String? = null,
    val maxInstances: Int = 0,  // 0 = auto (min(CPU/4, 2))
    val maxPerFile: Int = 4,
    val engineThreads: Int = 0,  // 0 = auto
    val idleTimeout: Duration = Duration.ofMinutes(5),
    val cleanupInterval: Duration = Duration.ofSeconds(10),
    val memoryCleanupInterval: Duration = Duration.ofHours(1),
    val maxCachedApks: Int = -1,  // -1 = no limit
    val uploadDir: Path = Path.of("./uploads"),
    val toolTimeout: Duration = Duration.ofMinutes(5),
    val xrefMode: XrefMode = XrefMode.JADX,
    /**
     * Runtime topology used for analysis requests. CURRENT preserves the
     * session-oriented engine pool behaviour; SHARED enables the per-file
     * runtime admission and snapshot fast path used by the bounded-memory
     * five-subagent mode.
     */
    val runtimeMode: RuntimeMode = RuntimeMode.CURRENT,
    /** Maximum number of mutable JADX engines resident for one APK in SHARED mode. */
    val sharedEngineSlots: Int = 1,
)

enum class XrefMode { TEXT, JADX }

enum class RuntimeMode { CURRENT, SHARED }
