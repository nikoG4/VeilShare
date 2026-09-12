package dev.veilshare.core.transfer

import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SignalingPeerMessengerTest {
    @Test
    fun `offer crosses typed messenger blind relay inbox and peer codec`() = runTest {
        val client = CapturingSignalingClient()
        val sessionId = SessionId("session-messenger")
        val transferId = TransferId("transfer-messenger")
        val peerCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val messenger = SignalingPeerMessenger(client, sessionId, transferId, peerCode)
        val offer = TransferOffer(
            fileId = FileId("file-messenger"),
            displayName = "report.pdf",
            mimeHint = "application/pdf",
            sizeBytes = 99_999,
            totalChunks = 1,
        )

        messenger.send(DecodedPeerMessage.Offer(offer))

        val request = assertNotNull(client.lastRelay)
        assertEquals(sessionId, request.sessionId)
        assertEquals(peerCode, request.toReferenceCode)
        assertEquals(true, request.opaquePayload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES)

        // Simulate exactly what signaling server handleRelay forwards to the target socket.
        val forwarded = SignalingEnvelope(
            protocolVersion = SharingProtocol.VERSION,
            messageId = MessageId("relay-forward"),
            type = MessageType.RELAY,
            sessionId = request.sessionId,
            payload = request.opaquePayload,
        )
        val peerEnvelope = SignalingPeerInbox().decodeRelay(forwarded)
        val decoded = PeerMessageCodec().decode(peerEnvelope)
        val decodedOffer = assertIs<DecodedPeerMessage.Offer>(decoded).value

        assertEquals(sessionId, peerEnvelope.sessionId)
        assertEquals(transferId, peerEnvelope.transferId)
        assertEquals(offer, decodedOffer)
    }

    @Test
    fun `inbox rejects signaling route and peer envelope session mismatch`() = runTest {
        val client = CapturingSignalingClient()
        val sessionId = SessionId("session-a")
        val messenger = SignalingPeerMessenger(
            client,
            sessionId,
            TransferId("transfer-a"),
            ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
        )
        messenger.send(
            DecodedPeerMessage.Offer(
                TransferOffer(FileId("file"), "safe.bin", sizeBytes = 1, totalChunks = 1),
            ),
        )
        val request = assertNotNull(client.lastRelay)

        val wrongRoute = SignalingEnvelope(
            protocolVersion = SharingProtocol.VERSION,
            messageId = MessageId("relay-wrong-route"),
            type = MessageType.RELAY,
            sessionId = SessionId("session-b"),
            payload = request.opaquePayload,
        )
        assertFailsWith<IllegalArgumentException> {
            SignalingPeerInbox().decodeRelay(wrongRoute)
        }
    }

    private class CapturingSignalingClient : SignalingClient {
        private val events = MutableSharedFlow<SignalingEnvelope>()
        override val incoming: Flow<SignalingEnvelope> = events
        var lastRelay: RelayRequest? = null

        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse =
            LookupResponse(status = LookupStatus.NOT_FOUND)

        override suspend fun relay(request: RelayRequest) {
            lastRelay = request
        }

        override suspend fun close() = Unit
    }
}
