package dev.veilshare.signaling

import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.KtorSignalingClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KtorSignalingClientIntegrationTest {
    @Test fun twoClientsRegisterLookupRelayAndUnregisterPresence() = testApplication {
        val state = SignalingServerState(clock = MutableSignalingClock())
        application { signalingModule(state) }
        val http = createClient { install(WebSockets) }
        val aliceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val bobCode = ReferenceCodes.parse("2345-6789-ABCD-EFGJ")
        val aliceId = SharingIdentityId("alice")
        val bobId = SharingIdentityId("bob")
        val alice = KtorSignalingClient(http, "/v1/ws", CountingEntropy(10))
        val bob = KtorSignalingClient(http, "/v1/ws", CountingEntropy(40))
        val opaque = "opaque peer payload".encodeToByteArray()

        try {
            alice.connect()
            bob.connect()
            alice.register(RegisterRequest(aliceId, aliceCode, "alice-public-key"))
            bob.register(RegisterRequest(bobId, bobCode, "bob-public-key"))

            val lookup = alice.lookup(LookupRequest(bobCode, aliceId))
            assertEquals(LookupStatus.FOUND, lookup.status)
            assertEquals(bobId, lookup.sharingIdentityId)

            alice.relay(RelayRequest(bobCode, SessionId("session-1"), opaque))
            val relayed = withTimeout(5_000) { bob.incoming.first() }
            assertEquals(MessageType.RELAY, relayed.type)
            assertEquals(SessionId("session-1"), relayed.sessionId)
            assertContentEquals(opaque, relayed.payload)

            // Explicit revocation must take effect immediately; callers must not have to
            // wait for connection teardown or presence TTL before rotating a route.
            bob.unregister(UnregisterRequest(bobId))
            val revoked = alice.lookup(LookupRequest(bobCode, aliceId))
            assertEquals(LookupStatus.NOT_FOUND, revoked.status)
        } finally {
            alice.close()
            bob.close()
        }

        val verifier = KtorSignalingClient(http, "/v1/ws", CountingEntropy(80))
        try {
            verifier.connect()
            val lookup = verifier.lookup(LookupRequest(bobCode, SharingIdentityId("verifier")))
            assertEquals(LookupStatus.NOT_FOUND, lookup.status)
        } finally {
            verifier.close()
        }
    }
}

private class CountingEntropy(seed: Int) : RandomBytesSource {
    private var next = seed

    override fun nextBytes(size: Int): ByteArray =
        ByteArray(size) { (next++ and 0xff).toByte() }
}
