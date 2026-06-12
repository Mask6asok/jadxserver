package jadx.server.fixture

import jadx.server.config.ServerConfig
import jadx.server.config.TransportMode
import jadx.server.server.ServerState
import jadx.server.tools.ToolRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Base class for reliability regression tests that exercise
 * [ServerState]-level MCP behaviour (polling, consistency, concurrency).
 *
 * Provides:
 * - [tempDir] — temporary directory, cleaned up after each test
 * - [state] — fully initialized [ServerState] in STDIO mode
 * - [registry] — [ToolRegistry] built from [state]
 * - [addFixtureFile] — writes a binary fixture file, registers in
 *   [state.fileIndex], returns the hash
 */
abstract class ServerStateTestBase {

    protected lateinit var tempDir: Path
    protected lateinit var state: ServerState
    protected lateinit var registry: ToolRegistry

    @BeforeTest
    fun baseSetUp() {
        tempDir = Files.createTempDirectory("jadx-test-server-state")
        val config = ServerConfig(uploadDir = tempDir)
        state = ServerState(config)
        registry = ToolRegistry.build(state, TransportMode.STDIO)
    }

    @AfterTest
    fun baseTearDown() {
        state.shutdown()
        tempDir.toFile().deleteRecursively()
    }

    // ── Helper factories ─────────────────────────────────────────────

    protected fun addFixtureFile(
        name: String = "test.apk",
        content: ByteArray = byteArrayOf(1, 2, 3, 4, 5)
    ): String {
        val filePath = tempDir.resolve(name)
        Files.write(filePath, content)
        return state.fileIndex.add(filePath, tempDir).hash
    }
}
