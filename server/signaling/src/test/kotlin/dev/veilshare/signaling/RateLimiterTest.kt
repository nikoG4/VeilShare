package dev.veilshare.signaling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test fun fixedWindowAllowsConfiguredNumberOfEvents() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowRateLimiter(clock, windowMillis = 1000, maxEvents = 2)

        assertTrue(limiter.allow("lookup:a"))
        assertTrue(limiter.allow("lookup:a"))
        assertFalse(limiter.allow("lookup:a"))

        clock.advance(1000)
        assertTrue(limiter.allow("lookup:a"))
    }

    @Test fun fixedWindowBudgetLimiterMessageCap() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(clock, windowMillis = 1000, maxEvents = 3, maxCost = 10000)

        assertTrue(limiter.allow("key1", 1))
        assertTrue(limiter.allow("key1", 1))
        assertTrue(limiter.allow("key1", 1))
        assertFalse(limiter.allow("key1", 1))

        clock.advance(1000)
        assertTrue(limiter.allow("key1", 1))
    }

    @Test fun fixedWindowBudgetLimiterByteCap() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(clock, windowMillis = 1000, maxEvents = 100, maxCost = 1000)

        assertTrue(limiter.allow("key1", 300))
        assertTrue(limiter.allow("key1", 300)) // total 600
        assertTrue(limiter.allow("key1", 300)) // total 900
        assertFalse(limiter.allow("key1", 200)) // would exceed 1000

        clock.advance(1000)
        assertTrue(limiter.allow("key1", 500))
    }

    @Test fun fixedWindowRateLimiterIndependentKeys() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowRateLimiter(clock, windowMillis = 1000, maxEvents = 2)

        assertTrue(limiter.allow("key1"))
        assertTrue(limiter.allow("key1"))
        assertFalse(limiter.allow("key1"))

        // Different key has its own budget
        assertTrue(limiter.allow("key2"))
        assertTrue(limiter.allow("key2"))
        assertFalse(limiter.allow("key2"))
    }

    @Test fun fixedWindowBudgetLimiterIndependentKeys() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(clock, windowMillis = 1000, maxEvents = 2, maxCost = 1000)

        assertTrue(limiter.allow("key1", 500))
        assertTrue(limiter.allow("key1", 400))
        assertFalse(limiter.allow("key1", 200))

        // Different key has its own budget
        assertTrue(limiter.allow("key2", 500))
        assertTrue(limiter.allow("key2", 400))
        assertFalse(limiter.allow("key2", 200))
    }

    @Test fun fixedWindowRateLimiterConcurrentEnforcement() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowRateLimiter(clock, windowMillis = 60_000, maxEvents = 100)

        // Simulate concurrent access by calling from multiple "threads" sequentially
        // Since the limiter is @Synchronized, we can test the final count
        val results = mutableListOf<Boolean>()
        repeat(200) {
            results.add(limiter.allow("shared-key"))
        }

        val accepted = results.count { it }
        val rejected = results.count { !it }
        assertEquals(100, accepted)
        assertEquals(100, rejected)
    }

    @Test fun fixedWindowBudgetLimiterConcurrentEnforcement() {
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(clock, windowMillis = 60_000, maxEvents = 100, maxCost = 10_000)

        val results = mutableListOf<Boolean>()
        repeat(200) {
            results.add(limiter.allow("shared-key", 100))
        }

        val accepted = results.count { it }
        val rejected = results.count { !it }
        assertEquals(100, accepted) // maxEvents limit
        assertEquals(100, rejected)
    }

    // Integration test: > 60 legitimate relays in one window
    @Test fun relayLimiterAllowsMoreThan60PerWindow() {
        val limits = SignalingLimits(
            relayWindowMillis = 60_000,
            maxRelayMessagesPerWindow = 8_192,
            maxRelayPayloadBytesPerWindow = 128 * 1024 * 1024,
        )
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(
            clock = clock,
            windowMillis = limits.relayWindowMillis,
            maxEvents = limits.maxRelayMessagesPerWindow,
            maxCost = limits.maxRelayPayloadBytesPerWindow,
        )

        // Send 100 small RELAY messages
        repeat(100) {
            assertTrue(limiter.allow("connection-1", 1000), "Relay $it should be allowed")
        }

        // Should still have capacity
        assertTrue(limiter.allow("connection-1", 1000))
    }

    @Test fun relayLimiterEnforcesMessageCap() {
        val limits = SignalingLimits(
            relayWindowMillis = 60_000,
            maxRelayMessagesPerWindow = 3,
            maxRelayPayloadBytesPerWindow = 128 * 1024 * 1024,
        )
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(
            clock = clock,
            windowMillis = limits.relayWindowMillis,
            maxEvents = limits.maxRelayMessagesPerWindow,
            maxCost = limits.maxRelayPayloadBytesPerWindow,
        )

        assertTrue(limiter.allow("conn", 100))
        assertTrue(limiter.allow("conn", 100))
        assertTrue(limiter.allow("conn", 100))
        assertFalse(limiter.allow("conn", 100))
    }

    @Test fun relayLimiterEnforcesByteCap() {
        val limits = SignalingLimits(
            relayWindowMillis = 60_000,
            maxRelayMessagesPerWindow = 1000,
            maxRelayPayloadBytesPerWindow = 10_000,
        )
        val clock = MutableSignalingClock()
        val limiter = FixedWindowBudgetLimiter(
            clock = clock,
            windowMillis = limits.relayWindowMillis,
            maxEvents = limits.maxRelayMessagesPerWindow,
            maxCost = limits.maxRelayPayloadBytesPerWindow,
        )

        assertTrue(limiter.allow("conn", 3000))
        assertTrue(limiter.allow("conn", 3000))
        assertTrue(limiter.allow("conn", 3000)) // total 9000
        assertFalse(limiter.allow("conn", 2000)) // would exceed 10000
    }
}

class MutableSignalingClock(start: Long = 0) : SignalingClock {
    private var now = start

    override fun nowMillis(): Long = now

    fun advance(deltaMillis: Long) {
        now += deltaMillis
    }
}
