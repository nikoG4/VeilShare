package dev.veilshare.core.transfer

import dev.veilshare.core.model.SessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeReplayGuardTest {
    @Test
    fun `session id can be claimed only once inside ttl`() = runTest {
        val clock = MutableClock()
        val guard = HandshakeReplayGuard(clock = clock, ttlMs = 1_000, maxEntries = 4)
        val session = SessionId("session-a")

        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(session))
        assertEquals(HandshakeSessionClaim.REPLAY, guard.claim(session))
        assertTrue(guard.contains(session))
        assertEquals(1, guard.size())
    }

    @Test
    fun `expired replay marker is released`() = runTest {
        val clock = MutableClock()
        val guard = HandshakeReplayGuard(clock = clock, ttlMs = 1_000, maxEntries = 4)
        val session = SessionId("session-a")

        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(session))
        clock.advance(999)
        assertEquals(HandshakeSessionClaim.REPLAY, guard.claim(session))
        clock.advance(1)
        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(session))
    }

    @Test
    fun `cache fails closed at capacity instead of evicting active markers`() = runTest {
        val clock = MutableClock()
        val guard = HandshakeReplayGuard(clock = clock, ttlMs = 10_000, maxEntries = 2)

        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(SessionId("a")))
        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(SessionId("b")))
        assertEquals(HandshakeSessionClaim.CAPACITY_EXCEEDED, guard.claim(SessionId("c")))
        assertEquals(HandshakeSessionClaim.REPLAY, guard.claim(SessionId("a")))
        assertEquals(2, guard.size())
    }

    @Test
    fun `expiry frees bounded capacity`() = runTest {
        val clock = MutableClock()
        val guard = HandshakeReplayGuard(clock = clock, ttlMs = 100, maxEntries = 1)

        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(SessionId("a")))
        assertEquals(HandshakeSessionClaim.CAPACITY_EXCEEDED, guard.claim(SessionId("b")))
        clock.advance(100)
        assertEquals(HandshakeSessionClaim.CLAIMED, guard.claim(SessionId("b")))
        assertFalse(guard.contains(SessionId("a")))
    }

    private class MutableClock : TransferClock {
        private var now = 0L
        override fun nowMillis(): Long = now
        fun advance(delta: Long) {
            now += delta
        }
    }
}
