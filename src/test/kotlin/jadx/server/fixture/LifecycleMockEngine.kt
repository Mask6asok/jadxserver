package jadx.server.fixture

import jadx.server.engine.DecompilerEngine
import jadx.server.engine.EngineInstance
import jadx.server.engine.EngineOptions
import jadx.server.engine.InstanceHealth
import jadx.server.mcp.McpToolDef
import java.nio.file.Path

/**
 * A [DecompilerEngine] with controllable lifecycle behaviours for testing
 * timeout, busy-worker, engine-failure, and stuck-state scenarios.
 *
 * All delays are controlled via millisecond fields rather than wall-clock
 * sleeps in assertions — use [openDelayMs] to simulate slow engine open
 * without brittle timing in test assertions.
 *
 * Default config produces a normal fast engine (identical to MockEngine).
 * Override fields before each test scenario.
 */
class LifecycleMockEngine(
    /** Simulated delay in [open] — inject instead of real APK parsing. */
    var openDelayMs: Long = 0,

    /** If non-null, [open] throws RuntimeException with this message. */
    var openShouldThrow: String? = null,

    /** Health response returned from [health]. */
    var healthResponse: InstanceHealth = InstanceHealth.HEALTHY
) : DecompilerEngine {

    override val name: String = "lifecycle-mock"

    /** Tracks calls for post-mortem assertions. Reset via [resetCounters]. */
    var openCallCount: Int = 0; private set
    var closeCallCount: Int = 0; private set

    private val schemas: List<McpToolDef> = listOf(
        McpToolDef("list_classes", "List classes")
            .param("filter", "string", "Filter")
            .param("offset", "number", "Offset")
            .param("count", "number", "Count"),
        McpToolDef("get_class_code", "Get class code")
            .param("class_name", "string", "Class name", required = true),
        McpToolDef("search_code", "Search code")
            .param("query", "string", "Query", required = true)
            .param("limit", "number", "Limit"),
    )

    override fun toolSchemas(): List<McpToolDef> = schemas

    override fun open(file: Path, options: EngineOptions): EngineInstance {
        openCallCount++
        openShouldThrow?.let { throw RuntimeException(it) }
        if (openDelayMs > 0) Thread.sleep(openDelayMs)

        return EngineInstance(
            engineName = name,
            fileHash = "lmock_${file.fileName}",
            state = LifecycleMockApk(file.fileName.toString())
        )
    }

    override fun close(instance: EngineInstance) {
        closeCallCount++
    }

    override fun health(instance: EngineInstance): InstanceHealth = healthResponse

    fun resetCounters() {
        openCallCount = 0
        closeCallCount = 0
    }
}

/**
 * Simple marker object stored as [EngineInstance.state] for engines created
 * by [LifecycleMockEngine].
 *
 * NOT a [jadx.server.engine.DecompiledApk] — analysis-tool-level testing
 * must use the full integration path with [jadx.server.engine.JadxEngine].
 * This is intentionally a plain object to test pool-level lifecycle only.
 */
class LifecycleMockApk(val fileName: String)
