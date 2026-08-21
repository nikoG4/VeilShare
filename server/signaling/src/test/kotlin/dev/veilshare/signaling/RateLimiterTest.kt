package dev.veilshare.signaling

import kotlin.test.Test
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
}

class MutableSignalingClock(start: Long = 0) : SignalingClock {
    private var now = start

    override fun nowMillis(): Long = now

    fun advance(deltaMillis: Long) {
        now += deltaMillis
    }
}
