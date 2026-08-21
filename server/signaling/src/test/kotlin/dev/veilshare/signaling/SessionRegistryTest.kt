package dev.veilshare.signaling

import dev.veilshare.core.model.ConnectionId
import dev.veilshare.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionRegistryTest {
    @Test fun createLookupActivateCloseAndExpire() {
        val clock = MutableSignalingClock()
        val registry = SessionRegistry(clock, SignalingLimits(sessionTtlMillis = 1000))
        val sessionId = SessionId("session")

        registry.create(sessionId, ConnectionId("sender"), ConnectionId("receiver"))
        assertNotNull(registry.lookup(sessionId))
        assertEquals(RelaySessionState.ACTIVE, registry.activate(sessionId).state)

        clock.advance(1001)
        assertNull(registry.lookup(sessionId))
    }

    @Test fun duplicateAndMaxLimitsFailClosed() {
        val registry = SessionRegistry(MutableSignalingClock(), SignalingLimits(maxPendingSessions = 1))
        registry.create(SessionId("one"), ConnectionId("sender"), ConnectionId("receiver"))

        assertFailsWith<IllegalArgumentException> {
            registry.create(SessionId("one"), ConnectionId("sender"), ConnectionId("receiver"))
        }
        assertFailsWith<IllegalArgumentException> {
            registry.create(SessionId("two"), ConnectionId("sender"), ConnectionId("receiver"))
        }
    }

    @Test fun closingConnectionDropsSessions() {
        val registry = SessionRegistry(MutableSignalingClock())
        registry.create(SessionId("one"), ConnectionId("sender"), ConnectionId("receiver"))
        registry.create(SessionId("two"), ConnectionId("other"), ConnectionId("sender"))

        assertEquals(2, registry.closeConnection(ConnectionId("sender")))
        assertEquals(0, registry.size())
    }
}
