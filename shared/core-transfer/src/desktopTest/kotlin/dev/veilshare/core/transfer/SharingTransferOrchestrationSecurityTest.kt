package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.PinnedPeerIdentity
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.HandshakeKeys
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingPublicIdentity
import dev.veilshare.core.model.ContactId
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.model.TransferReject
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SharingTransferOrchestrationSecurityTest {
    @Test
    fun `offer chunk count must match size and configured chunk size`() {
        val config = TransferConfig(chunkSize = 1_024, maxTransferBytes = 8_192)
        val inconsistent = TransferOffer(
            fileId = FileId("file-offer-mismatch"),
            displayName = "mismatch.bin",
            sizeBytes = 1_500,
            totalChunks = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            validateTransferOffer(inconsistent, config)
        }
    }

    @Test
    fun `offer above local transfer byte cap fails before receiver state exists`() {
        val config = TransferConfig(chunkSize = 1_024, maxTransferBytes = 1_024)
        val oversized = TransferOffer(
            fileId = FileId("file-offer-large"),
            displayName = "large.bin",
            sizeBytes = 2_048,
            totalChunks = 2,
        )

        assertFailsWith<IllegalArgumentException> {
            validateTransferOffer(oversized, config)
        }
    }

    @Test
    fun `DATA before ACCEPT has zero decryptor side effects`() = runTest {
        val production = DesktopProductionCrypto.create()
        val pair = sessionPair()
        val clients = clientPair()
        val transferId = TransferId("preaccept-transfer")
        val fileId = FileId("preaccept-file")
        val cryptoA = EstablishedTransferCrypto.open(
            pair.first,
            EstablishedSessionSide.INITIATOR,
            transferId,
            clients.first,
            production.cipher,
            production.random,
        )
        val cryptoB = EstablishedTransferCrypto.open(
            pair.second,
            EstablishedSessionSide.RESPONDER,
            transferId,
            clients.second,
            production.cipher,
            production.random,
        )
        val decryptor = CountingDecryptor()
        val receiver = InMemoryTransferReceiver(decryptor)
        val incoming = IncomingSharingTransfer(
            session = pair.second,
            transferId = transferId,
            offer = TransferOffer(
                fileId = fileId,
                displayName = "preaccept.bin",
                sizeBytes = 16,
                totalChunks = 1,
            ),
            crypto = cryptoB,
            receiver = receiver,
        )

        try {
            cryptoA.messenger.send(
                DecodedPeerMessage.Data(
                    TransferData(
                        transferIdHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray()),
                        fileIdHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray()),
                        chunkIndex = 0,
                        totalChunks = 1,
                        ciphertext = ByteArray(16) { 7 },
                        nonce = ByteArray(12) { 3 },
                    ),
                ),
            )

            assertFailsWith<IllegalStateException> {
                incoming.dispatch(clients.second.removeFirst())
            }
            assertEquals(0, decryptor.calls)
            assertEquals(null, receiver.getProgress(transferId).value)
        } finally {
            cryptoA.close()
            cryptoB.close()
            pair.first.close()
            pair.second.close()
        }
    }

    @Test
    fun `remote REJECT closes unsent source exactly once`() = runTest {
        val production = DesktopProductionCrypto.create()
        val pair = sessionPair()
        val clients = clientPair()
        val transferId = TransferId("reject-transfer")
        val fileId = FileId("reject-file")
        val cryptoA = EstablishedTransferCrypto.open(
            pair.first,
            EstablishedSessionSide.INITIATOR,
            transferId,
            clients.first,
            production.cipher,
            production.random,
        )
        val cryptoB = EstablishedTransferCrypto.open(
            pair.second,
            EstablishedSessionSide.RESPONDER,
            transferId,
            clients.second,
            production.cipher,
            production.random,
        )
        val source = CountingSource(ByteArray(64) { 1 })
        val outgoing = OutgoingSharingTransfer(
            session = pair.first,
            transferId = transferId,
            fileId = fileId,
            source = source,
            crypto = cryptoA,
            sender = DefaultTransferSender(production.random),
        )

        try {
            outgoing.sendOffer()
            cryptoB.messenger.send(
                DecodedPeerMessage.Reject(TransferReject(fileId, "declined")),
            )
            val result = outgoing.handleControl(clients.first.removeFirst())
            assertIs<OutgoingControlResult.Rejected>(result)
            assertEquals(OutgoingTransferState.REJECTED, outgoing.state)
            assertEquals(1, source.closeCalls)

            outgoing.abandonBeforeSend()
            assertEquals(1, source.closeCalls)
        } finally {
            cryptoA.close()
            cryptoB.close()
            pair.first.close()
            pair.second.close()
        }
    }

    private class CountingDecryptor : TransferDecryptor {
        var calls = 0
        override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray {
            calls++
            return ciphertext.copyOf()
        }
        override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray {
            calls++
            return ciphertext.copyOf()
        }
    }

    private class CountingSource(private val bytes: ByteArray) : TransferSource {
        var closeCalls = 0
        override val fileSize: Long = bytes.size.toLong()
        override val displayName: String = "counting.bin"
        override val mimeHint: String? = "application/octet-stream"
        override suspend fun readChunk(offset: Long, size: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            val start = offset.toInt()
            return bytes.copyOfRange(start, minOf(bytes.size, start + size))
        }
        override suspend fun close() {
            closeCalls++
        }
    }

    private fun sessionPair(): Pair<EstablishedPeerSession, EstablishedPeerSession> {
        val sessionId = SessionId("security-session")
        return established(sessionId, 1, 2, "2345-6789-ABCD-EFGH", "JKLM-NPQR-STUV-WXYZ") to
            established(sessionId, 2, 1, "JKLM-NPQR-STUV-WXYZ", "2345-6789-ABCD-EFGH")
    }

    private fun established(
        sessionId: SessionId,
        localMarker: Int,
        peerMarker: Int,
        localCode: String,
        peerCode: String,
    ) = EstablishedPeerSession(
        sessionId = sessionId,
        peer = PinnedPeerIdentity(
            contactId = ContactId("contact-$peerMarker"),
            sharingIdentityId = SharingIdentityId("peer-$peerMarker"),
            sharingIdentityIdHash = "hash-$peerMarker",
            publicKey = Ed25519PublicKey(ByteArray(32) { (peerMarker + it).toByte() }),
            fingerprint = Fingerprint("peer-fingerprint-$peerMarker"),
        ),
        localIdentity = SharingPublicIdentity(
            contextId = SharingContextId("ctx-$localMarker"),
            identityId = SharingIdentityId("local-$localMarker"),
            publicKey = Ed25519PublicKey(ByteArray(32) { (localMarker + it + 20).toByte() }),
            fingerprint = Fingerprint("local-fingerprint-$localMarker"),
        ),
        localReferenceCode = ReferenceCodes.parse(localCode),
        peerReferenceCode = ReferenceCodes.parse(peerCode),
        keys = HandshakeKeys(
            senderToReceiverDataKey = ByteArray(32) { (31 + it).toByte() },
            receiverToSenderDataKey = ByteArray(32) { (63 + it).toByte() },
            senderToReceiverEnvelopeKey = ByteArray(32) { (95 + it).toByte() },
            receiverToSenderEnvelopeKey = ByteArray(32) { (127 + it).toByte() },
            transcriptHash = "security-transcript",
        ),
    )

    private fun clientPair(): Pair<LoopbackClient, LoopbackClient> {
        val first = LoopbackClient("first")
        val second = LoopbackClient("second")
        first.peer = second
        second.peer = first
        return first to second
    }

    private class LoopbackClient(private val label: String) : SignalingClient {
        private val events = MutableSharedFlow<SignalingEnvelope>()
        private val queue = ArrayDeque<SignalingEnvelope>()
        private var counter = 0
        lateinit var peer: LoopbackClient
        override val incoming: Flow<SignalingEnvelope> = events
        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun unregister(request: UnregisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse = LookupResponse(LookupStatus.NOT_FOUND)
        override suspend fun relay(request: RelayRequest) {
            peer.queue.addLast(
                SignalingEnvelope(
                    protocolVersion = SharingProtocol.VERSION,
                    messageId = MessageId("$label-${counter++}"),
                    type = MessageType.RELAY,
                    sessionId = request.sessionId,
                    payload = request.opaquePayload.copyOf(),
                ),
            )
        }
        override suspend fun close() = Unit
        fun removeFirst(): SignalingEnvelope = queue.removeFirst()
    }
}
