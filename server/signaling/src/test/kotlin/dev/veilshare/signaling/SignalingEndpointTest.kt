package dev.veilshare.signaling

import dev.veilshare.core.model.ErrorMessage
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.SignalingEnvelope
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.async
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SignalingEndpointTest {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test fun registerLookupRelayAndDisconnectCleanup() = testApplication {
        val state = SignalingServerState(clock = MutableSignalingClock())
        application { signalingModule(state) }
        val client = createClient { install(WebSockets) }
        val receiverCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val senderCode = ReferenceCodes.parse("2345-6789-ABCD-EFGJ")
        val opaque = byteArrayOf(9, 8, 7, 6)

        client.webSocket("/v1/ws") {
            sendEnvelope(registerEnvelope("register-b", receiverCode, "receiver", "receiver-key"))
            assertEquals(MessageType.REGISTER, receiveEnvelope().type)

            val receiver = this
            val senderJob = async {
                client.webSocket("/v1/ws") {
                    sendEnvelope(registerEnvelope("register-a", senderCode, "sender", "sender-key"))
                    assertEquals(MessageType.REGISTER, receiveEnvelope().type)

                    sendEnvelope(
                        envelope(
                            "lookup-b",
                            MessageType.LOOKUP,
                            LookupRequest(receiverCode, SharingIdentityId("sender")),
                        )
                    )
                    val lookup = receiveEnvelope()
                    val response = json.decodeFromString<LookupResponse>(lookup.payload.decodeToString())
                    assertEquals(LookupStatus.FOUND, response.status)
                    assertEquals(SharingIdentityId("receiver"), response.sharingIdentityId)

                    sendEnvelope(
                        envelope(
                            "relay",
                            MessageType.RELAY,
                            RelayRequest(receiverCode, SessionId("session"), opaque),
                        )
                    )
                }
            }

            val relayed = receiver.receiveEnvelope()
            assertEquals(MessageType.RELAY, relayed.type)
            assertEquals(SessionId("session"), relayed.sessionId)
            assertContentEquals(opaque, relayed.payload)
            senderJob.await()
        }

        client.webSocket("/v1/ws") {
            sendEnvelope(
                envelope(
                    "lookup-after-disconnect",
                    MessageType.LOOKUP,
                    LookupRequest(receiverCode, SharingIdentityId("sender")),
                )
            )
            val lookup = receiveEnvelope()
            val response = json.decodeFromString<LookupResponse>(lookup.payload.decodeToString())
            assertEquals(LookupStatus.NOT_FOUND, response.status)
        }
    }

    @Test fun malformedUnsupportedAndOversizedFramesFailClosed() = testApplication {
        application { signalingModule(SignalingServerState(clock = MutableSignalingClock())) }
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send("not-json")
            assertEquals(MessageType.ERROR, receiveEnvelope().type)

            send(
                Frame.Text(
                    json.encodeToString(
                        SignalingEnvelope(
                            protocolVersion = SharingProtocol.VERSION,
                            messageId = MessageId("too-large"),
                            type = MessageType.RELAY,
                            payload = ByteArray(SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES),
                        )
                    ) + "x".repeat(SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES + 1)
                )
            )
            assertEquals(MessageType.ERROR, receiveEnvelope().type)
        }
    }

    @Test fun lookupRateLimitReturnsError() = testApplication {
        val limits = SignalingLimits(maxLookupsPerWindow = 1)
        application { signalingModule(SignalingServerState(clock = MutableSignalingClock(), limits = limits)) }
        val client = createClient { install(WebSockets) }
        val code = ReferenceCodes.parse("2345-6789-ABCD-EFGH")

        client.webSocket("/v1/ws") {
            sendEnvelope(envelope("lookup-1", MessageType.LOOKUP, LookupRequest(code, SharingIdentityId("sender"))))
            assertEquals(MessageType.LOOKUP, receiveEnvelope().type)

            sendEnvelope(envelope("lookup-2", MessageType.LOOKUP, LookupRequest(code, SharingIdentityId("sender"))))
            val error = receiveEnvelope()
            assertEquals(MessageType.ERROR, error.type)
            val payload = json.decodeFromString<ErrorMessage>(error.payload.decodeToString())
            assertEquals(dev.veilshare.core.model.ErrorCode.RATE_LIMITED, payload.errorCode)
        }
    }

    private fun registerEnvelope(
        messageId: String,
        code: ReferenceCode,
        identity: String,
        publicKey: String,
    ): SignalingEnvelope = envelope(
        messageId,
        MessageType.REGISTER,
        RegisterRequest(SharingIdentityId(identity), code, publicKey),
    )

    private inline fun <reified T> envelope(
        messageId: String,
        type: MessageType,
        payload: T,
    ): SignalingEnvelope = SignalingEnvelope(
        protocolVersion = SharingProtocol.VERSION,
        messageId = MessageId(messageId),
        type = type,
        payload = json.encodeToString(payload).encodeToByteArray(),
    )

    private suspend fun io.ktor.websocket.WebSocketSession.sendEnvelope(envelope: SignalingEnvelope) {
        send(Frame.Text(json.encodeToString(envelope)))
    }

    private suspend fun io.ktor.websocket.WebSocketSession.receiveEnvelope(): SignalingEnvelope {
        val frame = incoming.receive() as Frame.Text
        return json.decodeFromString(frame.readText())
    }
}
