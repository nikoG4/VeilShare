package dev.veilshare.signaling

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimiterConcurrencyTest {
    @Test
    fun `fixed window never accepts above cap under real concurrent calls`() {
        val limiter = FixedWindowRateLimiter(
            clock = MutableSignalingClock(),
            windowMillis = 60_000,
            maxEvents = 100,
        )
        val results = runConcurrent(200) { limiter.allow("shared-key") }

        assertEquals(100, results.count { it })
        assertEquals(100, results.count { !it })
    }

    @Test
    fun `budget limiter enforces event and byte budget atomically under concurrency`() {
        val limiter = FixedWindowBudgetLimiter(
            clock = MutableSignalingClock(),
            windowMillis = 60_000,
            maxEvents = 100,
            maxCost = 10_000,
        )
        val results = runConcurrent(200) { limiter.allow("shared-key", 100) }

        assertEquals(100, results.count { it })
        assertEquals(100, results.count { !it })
    }

    private fun runConcurrent(taskCount: Int, action: () -> Boolean): List<Boolean> {
        val executor = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(taskCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(taskCount)
        val results = ConcurrentLinkedQueue<Boolean>()

        repeat(taskCount) {
            executor.execute {
                ready.countDown()
                start.await()
                try {
                    results.add(action())
                } finally {
                    done.countDown()
                }
            }
        }

        // Not all 200 tasks can occupy a 16-thread pool simultaneously, so waiting for
        // every task to signal readiness would deadlock. Let the worker pool fill, then release.
        while (ready.count > taskCount - 16L) {
            Thread.yield()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "Concurrent limiter tasks did not finish")
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate")
        return results.toList()
    }
}
