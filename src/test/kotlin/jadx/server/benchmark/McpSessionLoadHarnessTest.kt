package jadx.server.benchmark

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpSessionLoadHarnessTest {
    @Test
    fun `five activities use independent sessions and one file hash`() {
        val seen = Collections.synchronizedList(mutableListOf<McpActivity>())
        val load = McpSessionLoadHarness.five("apk-hash")

        val results = load.runConcurrently { activity ->
            seen += activity
            activity.sessionId
        }

        assertEquals(5, load.size)
        assertEquals(5, results.toSet().size)
        assertEquals(setOf("apk-hash"), seen.map { it.fileHash }.toSet())
        assertEquals(5, seen.map { it.sessionId }.toSet().size)
    }

    @Test
    fun `invalid activity sets are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            McpSessionLoadHarness(listOf(McpActivity("same", "a"), McpActivity("same", "a")))
        }
        assertFailsWith<IllegalArgumentException> {
            McpSessionLoadHarness(listOf(McpActivity("one", "a"), McpActivity("two", "b")))
        }
    }

    @Test
    fun `barrier overlaps activity execution`() {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maxActive = java.util.concurrent.atomic.AtomicInteger()
        McpSessionLoadHarness.five("apk-hash").runConcurrently {
            val current = active.incrementAndGet()
            maxActive.updateAndGet { old -> maxOf(old, current) }
            Thread.sleep(20)
            active.decrementAndGet()
        }
        assertTrue(maxActive.get() > 1, "activities did not overlap")
    }
}
