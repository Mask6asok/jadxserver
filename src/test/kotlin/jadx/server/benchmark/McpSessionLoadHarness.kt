package jadx.server.benchmark

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic load description for MCP benchmarks.
 *
 * Each activity has its own MCP session identity, while all activities carry
 * the same file hash.  The harness deliberately does not create a server or
 * touch EnginePool: callers can plug [Activity] into an HTTP MCP client (or
 * an in-process handler) and measure only the work they intend to measure.
 */
data class McpActivity(
    val sessionId: String,
    val fileHash: String,
)

class McpSessionLoadHarness(
    private val activities: List<McpActivity>,
) {
    init {
        require(activities.isNotEmpty()) { "At least one activity is required" }
        require(activities.map { it.sessionId }.toSet().size == activities.size) {
            "Every activity must use a distinct MCP session"
        }
        require(activities.map { it.fileHash }.toSet().size == 1) {
            "Every activity must use the same file_hash"
        }
    }

    val fileHash: String get() = activities.first().fileHash
    val size: Int get() = activities.size

    /** Run one activity per fixed worker and release them at the same barrier. */
    fun <T> runConcurrently(action: (McpActivity) -> T): List<T> {
        val ready = CountDownLatch(size)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(size)
        try {
            val failure = AtomicReference<Throwable?>(null)
            val futures = activities.map { activity ->
                executor.submit(Callable {
                    try {
                        ready.countDown()
                        check(ready.await(30, TimeUnit.SECONDS)) { "activities did not reach barrier" }
                        check(release.await(30, TimeUnit.SECONDS)) { "activities were not released" }
                        action(activity)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                        throw t
                    }
                })
            }
            check(ready.await(30, TimeUnit.SECONDS)) { "activities did not reach barrier" }
            release.countDown()
            val result = futures.map { it.get(30, TimeUnit.MINUTES) }
            failure.get()?.let { throw it }
            return result
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        fun five(fileHash: String, prefix: String = "bench-session"): McpSessionLoadHarness =
            McpSessionLoadHarness((1..5).map { McpActivity("$prefix-$it", fileHash) })
    }
}
