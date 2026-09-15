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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class KtorSignalingClientIntegrationTest {
    @Test
    fun healthEndpointIsAvailableWithoutOpeningAWebSocket() = testApplication {
        application { signalingModule() }
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }

    @Test
    fun twoClientsRegisterLookupRelayAndUnregisterPresence() = testApplication {
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

            val relayed = coroutineScope {
                // MutableSharedFlow intentionally has no replay. Subscribe before sending so
                // this test cannot race a fast local transport and drop the relayed frame.
                val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) { bob.incoming.first() }
                }
                alice.relay(RelayRequest(bobCode, SessionId("session-1"), opaque))
                awaiting.await()
            }
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

    @Test
    fun registeredPresenceIsRefreshedPastServerTtl() = testApplication {
        val state = SignalingServerState(
            clock = SignalingClock { System.currentTimeMillis() },
            limits = SignalingLimits(presenceTtlMillis = 150),
        )
        application { signalingModule(state) }
        val http = createClient { install(WebSockets) }
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val identity = SharingIdentityId("keepalive-client")
        val signaling = KtorSignalingClient(
            httpClient = http,
            endpointUrl = "/v1/ws",
            random = CountingEntropy(90),
            timeoutMillis = 2_000,
            registrationKeepAliveMillis = 35,
        )

        try {
            signaling.connect()
            signaling.register(RegisterRequest(identity, code, "public-key"))
            delay(450)
            val lookup = signaling.lookup(LookupRequest(code, identity))
            assertEquals(LookupStatus.FOUND, lookup.status)
            assertEquals(identity, lookup.sharingIdentityId)
        } finally {
            signaling.close()
        }
    }

    @Test
    fun clientCanReconnectAndReregisterAfterRemoteTransportDrop() = testApplication {
        val state = SignalingServerState(clock = MutableSignalingClock())
        application { signalingModule(state) }
        val http = createClient { install(WebSockets) }
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val identity = SharingIdentityId("reconnect-client")
        val signaling = KtorSignalingClient(http, "/v1/ws", CountingEntropy(120), timeoutMillis = 2_000)

        try {
            signaling.connect()
            signaling.register(RegisterRequest(identity, code, "public-key"))
            assertEquals(LookupStatus.FOUND, signaling.lookup(LookupRequest(code, identity)).status)

            withTimeout(5_000) {
                while (state.sockets.size != 1) delay(10)
            }
            val serverSocket = assertNotNull(state.sockets.values.singleOrNull())
            serverSocket.close(CloseReason(CloseReason.Codes.GOING_AWAY, "test transport drop"))

            withTimeout(5_000) {
                while (state.sockets.isNotEmpty()) delay(10)
            }
            // A request on the dropped transport must fail rather than hanging until a
            // successful-looking timeout path. The next explicit connect creates a fresh
            // transport; presence is then explicitly re-registered.
            withTimeout(5_000) {
                while (runCatching { signaling.lookup(LookupRequest(code, identity)) }.isSuccess) delay(10)
            }

            signaling.connect()
            signaling.register(RegisterRequest(identity, code, "public-key"))
            val afterReconnect = signaling.lookup(LookupRequest(code, identity))
            assertEquals(LookupStatus.FOUND, afterReconnect.status)
            assertEquals(identity, afterReconnect.sharingIdentityId)
        } finally {
            signaling.close()
        }
    }
}

private class CountingEntropy(seed: Int) : RandomBytesSource {
    private var next = seed

    override fun nextBytes(size: Int): ByteArray =
        ByteArray(size) { (next++ and 0xff).toByte() }
}
