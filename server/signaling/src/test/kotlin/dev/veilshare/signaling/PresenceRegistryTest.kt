package dev.veilshare.signaling

import dev.veilshare.core.model.ConnectionId
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceRegistryTest {
    @Test fun registerLookupUnregisterAndExpiry() {
        val clock = MutableSignalingClock()
        val registry = PresenceRegistry(clock, SignalingLimits(presenceTtlMillis = 1000))
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val connection = ConnectionId("connection-a")

        registry.register(code, connection, SharingIdentityId("identity-a"), "public-key")
        assertNotNull(registry.lookup(code))

        clock.advance(1001)
        assertNull(registry.lookup(code))
        assertEquals(0, registry.size())
    }

    @Test fun collisionFailsUnlessSameConnectionRefreshes() {
        val registry = PresenceRegistry(MutableSignalingClock())
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")

        registry.register(code, ConnectionId("a"), SharingIdentityId("identity-a"), "key-a")
        registry.register(code, ConnectionId("a"), SharingIdentityId("identity-a"), "key-a")

        assertFailsWith<IllegalArgumentException> {
            registry.register(code, ConnectionId("b"), SharingIdentityId("identity-b"), "key-b")
        }
    }

    @Test fun copiedPublicIdentityCannotTakeOverLiveReferenceCode() {
        val registry = PresenceRegistry(MutableSignalingClock())
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val identity = SharingIdentityId("identity-a")
        val oldConnection = ConnectionId("old")
        val attackerConnection = ConnectionId("attacker")

        registry.register(code, oldConnection, identity, "key-a")
        assertFailsWith<IllegalArgumentException> {
            registry.register(code, attackerConnection, identity, "key-a")
        }

        assertEquals(oldConnection, registry.lookup(code)?.connectionId)
        registry.unregisterConnection(oldConnection)
        assertNull(registry.lookup(code))
    }

    @Test fun perConnectionLimitIsBounded() {
        val registry = PresenceRegistry(
            MutableSignalingClock(),
            SignalingLimits(maxRegistrationsPerConnection = 1),
        )
        val connection = ConnectionId("a")
        registry.register(ReferenceCodes.parse("2345-6789-ABCD-EFGH"), connection, SharingIdentityId("a"), "key")

        assertFailsWith<IllegalArgumentException> {
            registry.register(ReferenceCodes.parse("2345-6789-ABCD-EFGJ"), connection, SharingIdentityId("a"), "key")
        }
    }

    @Test fun unregisterConnectionRemovesAllCodes() {
        val registry = PresenceRegistry(MutableSignalingClock())
        val connection = ConnectionId("a")
        val first = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val second = ReferenceCodes.parse("2345-6789-ABCD-EFGJ")

        registry.register(first, connection, SharingIdentityId("a"), "key")
        registry.register(second, connection, SharingIdentityId("a"), "key")
        registry.unregisterConnection(connection)

        assertNull(registry.lookup(first))
        assertNull(registry.lookup(second))
        assertTrue(registry.size() == 0)
    }
}
