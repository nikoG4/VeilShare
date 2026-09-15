package dev.veilshare.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignalingEndpointPolicyTest {
    @Test
    fun secureWebSocketEndpointIsAccepted() {
        assertEquals(
            "wss://relay.example.com/v1/ws",
            validateSignalingEndpoint("  wss://relay.example.com/v1/ws  "),
        )
    }

    @Test
    fun plainRemoteWebSocketIsRejectedEvenWhenLocalDevelopmentIsEnabled() {
        assertFailsWith<IllegalArgumentException> {
            validateSignalingEndpoint("ws://192.168.1.50:8080/v1/ws", allowInsecureLoopback = true)
        }
    }

    @Test
    fun localDevelopmentWebSocketRequiresExplicitOptIn() {
        assertFailsWith<IllegalArgumentException> {
            validateSignalingEndpoint("ws://127.0.0.1:8080/v1/ws")
        }
        assertEquals(
            "ws://127.0.0.1:8080/v1/ws",
            validateSignalingEndpoint("ws://127.0.0.1:8080/v1/ws", allowInsecureLoopback = true),
        )
        assertEquals(
            "ws://10.0.2.2:8080/v1/ws",
            validateSignalingEndpoint("ws://10.0.2.2:8080/v1/ws", allowInsecureLoopback = true),
        )
    }

    @Test
    fun malformedOrCredentialBearingEndpointsAreRejected() {
        listOf(
            "",
            "https://relay.example.com/v1/ws",
            "wss://",
            "wss://user:pass@relay.example.com/v1/ws",
            "wss://relay example.com/v1/ws",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                validateSignalingEndpoint(endpoint)
            }
        }
    }
}
