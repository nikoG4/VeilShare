package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionHello
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignalingPeerMessengerTest {
    @Test
    fun `post-handshake offer is opaque to relay and decrypts for intended peer`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val client = CapturingSignalingClient()
        val sessionId = SessionId("session-messenger")
        val transferId = TransferId("transfer-messenger-secret")
        val peerCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val keyBytes = ByteArray(32) { index -> (index * 5 + 9).toByte() }
        val senderKey = SensitiveBytes(keyBytes.copyOf())
        val receiverKey = SensitiveBytes(keyBytes.copyOf())

        try {
            val senderChannel = SecurePeerChannel(
                sessionId = sessionId,
                direction = PeerDirection.SENDER_TO_RECEIVER,
                cipher = crypto.cipher,
                sessionKey = senderKey,
                random = crypto.random,
            )
            val receiverChannel = SecurePeerChannel(
                sessionId = sessionId,
                direction = PeerDirection.SENDER_TO_RECEIVER,
                cipher = crypto.cipher,
                sessionKey = receiverKey,
                random = crypto.random,
            )
            val messenger = SecureSignalingPeerMessenger(
                signalingClient = client,
                sessionId = sessionId,
                transferId = transferId,
                peerReferenceCode = peerCode,
                channel = senderChannel,
            )
            val offer = TransferOffer(
                fileId = FileId("file-messenger"),
                displayName = "private-report.pdf",
                mimeHint = "application/pdf",
                sizeBytes = 99_999,
                totalChunks = 1,
            )

            messenger.send(DecodedPeerMessage.Offer(offer))

            val request = assertNotNull(client.lastRelay)
            assertEquals(sessionId, request.sessionId)
            assertEquals(peerCode, request.toReferenceCode)
            assertTrue(request.opaquePayload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES)

            val relayVisible = request.opaquePayload.decodeToString()
            assertFalse(relayVisible.contains("private-report.pdf"))
            assertFalse(relayVisible.contains("application/pdf"))
            assertFalse(relayVisible.contains(transferId.value))
            assertFalse(relayVisible.contains("OFFER"))

            val forwarded = SignalingEnvelope(
                protocolVersion = SharingProtocol.VERSION,
                messageId = MessageId("relay-forward"),
                type = MessageType.RELAY,
                sessionId = request.sessionId,
                payload = request.opaquePayload,
            )
            val peerEnvelope = SecureSignalingPeerInbox(sessionId, receiverChannel).decodeRelay(forwarded)
            val decodedOffer = assertIs<DecodedPeerMessage.Offer>(PeerMessageCodec().decode(peerEnvelope)).value

            assertEquals(sessionId, peerEnvelope.sessionId)
            assertEquals(transferId, peerEnvelope.transferId)
            assertEquals(offer, decodedOffer)
        } finally {
            senderKey.close()
            receiverKey.close()
            keyBytes.fill(0)
        }
    }

    @Test
    fun `wrong direction or wrong session cannot decrypt secure peer envelope`() = runTest {
        val crypto = DesktopProductionCrypto.create()
        val client = CapturingSignalingClient()
        val sessionId = SessionId("session-a")
        val transferId = TransferId("transfer-a")
        val keyBytes = ByteArray(32) { index -> (index + 31).toByte() }
        val senderKey = SensitiveBytes(keyBytes.copyOf())
        val wrongDirectionKey = SensitiveBytes(keyBytes.copyOf())
        val wrongSessionKey = SensitiveBytes(keyBytes.copyOf())

        try {
            val senderChannel = SecurePeerChannel(
                sessionId,
                PeerDirection.SENDER_TO_RECEIVER,
                crypto.cipher,
                senderKey,
                crypto.random,
            )
            val messenger = SecureSignalingPeerMessenger(
                client,
                sessionId,
                transferId,
                ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
                senderChannel,
            )
            messenger.send(
                DecodedPeerMessage.Offer(
                    TransferOffer(FileId("file"), "safe.bin", sizeBytes = 1, totalChunks = 1),
                ),
            )
            val request = assertNotNull(client.lastRelay)
            val forwarded = SignalingEnvelope(
                protocolVersion = SharingProtocol.VERSION,
                messageId = MessageId("relay"),
                type = MessageType.RELAY,
                sessionId = sessionId,
                payload = request.opaquePayload,
            )

            val wrongDirection = SecurePeerChannel(
                sessionId,
                PeerDirection.RECEIVER_TO_SENDER,
                crypto.cipher,
                wrongDirectionKey,
                crypto.random,
            )
            assertFailsWith<SecurityException> {
                SecureSignalingPeerInbox(sessionId, wrongDirection).decodeRelay(forwarded)
            }

            val otherSession = SessionId("session-b")
            val wrongSession = SecurePeerChannel(
                otherSession,
                PeerDirection.SENDER_TO_RECEIVER,
                crypto.cipher,
                wrongSessionKey,
                crypto.random,
            )
            assertFailsWith<IllegalArgumentException> {
                SecureSignalingPeerInbox(otherSession, wrongSession).decodeRelay(forwarded)
            }
        } finally {
            senderKey.close()
            wrongDirectionKey.close()
            wrongSessionKey.close()
            keyBytes.fill(0)
        }
    }

    @Test
    fun `public handshake relay contains no transfer identifier`() = runTest {
        val client = CapturingSignalingClient()
        val sessionId = SessionId("handshake-session")
        val messenger = HandshakeSignalingMessenger(
            signalingClient = client,
            sessionId = sessionId,
            peerReferenceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
        )
        val hello = SessionHello(
            sharingIdentityIdHash = "sender-identity-hash",
            sharingPublicKey = "public-key",
            sessionIdHash = "session-hash",
            signature = "signature",
        )

        messenger.send(DecodedPeerMessage.Hello(hello))

        val request = assertNotNull(client.lastRelay)
        val visible = request.opaquePayload.decodeToString()
        assertFalse(visible.contains("transferId"))
        val forwarded = SignalingEnvelope(
            protocolVersion = SharingProtocol.VERSION,
            messageId = MessageId("handshake-forward"),
            type = MessageType.RELAY,
            sessionId = sessionId,
            payload = request.opaquePayload,
        )
        val decoded = assertIs<DecodedPeerMessage.Hello>(HandshakeSignalingInbox().decodeRelay(forwarded))
        assertEquals(hello, decoded.value)
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
