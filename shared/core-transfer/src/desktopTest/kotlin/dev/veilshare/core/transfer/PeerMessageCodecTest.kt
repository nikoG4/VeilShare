package dev.veilshare.core.transfer

import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.PeerMessageType
import dev.veilshare.core.model.SessionConfirm
import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionHello
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.TransferAccept
import dev.veilshare.core.model.TransferCancel
import dev.veilshare.core.model.TransferComplete
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferFailure
import dev.veilshare.core.model.TransferFailureCode
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.model.TransferReject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PeerMessageCodecTest {
    private val codec = PeerMessageCodec()
    private val sessionId = SessionId("session-1")
    private val transferId = TransferId("transfer-1")

    @Test
    fun `all peer message types encode and decode through one strict gateway`() {
        val messages = listOf<DecodedPeerMessage>(
            DecodedPeerMessage.Hello(
                SessionHello(
                    sharingIdentityIdHash = "sender-hash",
                    sharingPublicKey = "sender-key",
                    sessionIdHash = "session-hash",
                    signature = "signature",
                ),
            ),
            DecodedPeerMessage.Confirm(
                SessionConfirm(
                    sharingIdentityIdHash = "receiver-hash",
                    sharingPublicKey = "receiver-key",
                    sessionIdHash = "session-hash",
                    receiverEphemeralPublicKey = "receiver-ephemeral",
                    signature = "signature",
                ),
            ),
            DecodedPeerMessage.ConfirmAck(
                SessionConfirmAck(
                    senderIdentityIdHash = "sender-hash",
                    sessionIdHash = "session-hash",
                    senderEphemeralPublicKey = "sender-ephemeral",
                    transcriptHash = "transcript-hash",
                    signature = "signature",
                ),
            ),
            DecodedPeerMessage.Offer(
                TransferOffer(
                    fileId = FileId("file-1"),
                    displayName = "photo.jpg",
                    mimeHint = "image/jpeg",
                    sizeBytes = 3,
                    totalChunks = 1,
                ),
            ),
            DecodedPeerMessage.Accept(TransferAccept(FileId("file-1"))),
            DecodedPeerMessage.Reject(TransferReject(FileId("file-1"), "user declined")),
            DecodedPeerMessage.Data(
                TransferData(
                    transferIdHash = "transfer-hash",
                    fileIdHash = "file-hash",
                    chunkIndex = 0,
                    totalChunks = 1,
                    ciphertext = byteArrayOf(1, 2, 3),
                    nonce = ByteArray(12) { 4 },
                ),
            ),
            DecodedPeerMessage.Complete(TransferComplete("transfer-hash", "file-hash", 1)),
            DecodedPeerMessage.Cancel(TransferCancel("transfer-hash", "user cancelled")),
            DecodedPeerMessage.Failure(
                TransferFailure(
                    transferIdHash = "transfer-hash",
                    code = TransferFailureCode.IO_ERROR,
                    details = "transport failed",
                ),
            ),
        )

        for (message in messages) {
            val envelope = codec.encode(sessionId, transferId, message)
            assertEquals(sessionId, envelope.sessionId)
            assertEquals(transferId, envelope.transferId)
            assertEquals(expectedType(message), envelope.messageType)

            val decoded = codec.decode(envelope)
            assertEquals(message::class, decoded::class)
            if (message is DecodedPeerMessage.Data) {
                val decodedData = assertIs<DecodedPeerMessage.Data>(decoded).value
                assertContentEquals(message.value.ciphertext, decodedData.ciphertext)
                assertContentEquals(message.value.nonce, decodedData.nonce)
                assertEquals(message.value.transferIdHash, decodedData.transferIdHash)
                assertEquals(message.value.fileIdHash, decodedData.fileIdHash)
            }
        }
    }

    @Test
    fun `codec rejects empty or type-confused payload`() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                PeerEnvelope(
                    protocolVersion = 1,
                    messageType = PeerMessageType.DATA,
                    sessionId = sessionId,
                    transferId = transferId,
                    payload = ByteArray(0),
                ),
            )
        }

        val offerEnvelope = codec.encode(
            sessionId,
            transferId,
            DecodedPeerMessage.Offer(
                TransferOffer(FileId("file"), "safe.bin", sizeBytes = 1, totalChunks = 1),
            ),
        )
        assertFailsWith<Exception> {
            codec.decode(offerEnvelope.copy(messageType = PeerMessageType.ACCEPT))
        }
    }

    private fun expectedType(message: DecodedPeerMessage): PeerMessageType = when (message) {
        is DecodedPeerMessage.Hello -> PeerMessageType.SESSION_HELLO
        is DecodedPeerMessage.Confirm -> PeerMessageType.SESSION_CONFIRM
        is DecodedPeerMessage.ConfirmAck -> PeerMessageType.SESSION_CONFIRM_ACK
        is DecodedPeerMessage.Offer -> PeerMessageType.OFFER
        is DecodedPeerMessage.Accept -> PeerMessageType.ACCEPT
        is DecodedPeerMessage.Reject -> PeerMessageType.REJECT
        is DecodedPeerMessage.Data -> PeerMessageType.DATA
        is DecodedPeerMessage.Complete -> PeerMessageType.COMPLETE
        is DecodedPeerMessage.Cancel -> PeerMessageType.CANCEL
        is DecodedPeerMessage.Failure -> PeerMessageType.FAILURE
    }
}
