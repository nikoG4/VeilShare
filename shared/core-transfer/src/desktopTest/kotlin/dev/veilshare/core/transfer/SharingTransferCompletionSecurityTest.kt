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
import dev.veilshare.core.model.TransferCancel
import dev.veilshare.core.model.TransferComplete
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingTransferCompletionSecurityTest {
    @Test
    fun `COMPLETE before authenticated DATA completion is rejected`() = runTest {
        val fixture = fixture(chunkSize = 8)
        try {
            fixture.incoming.accept()
            // ACCEPT goes to initiator; it is irrelevant to this receiver-side invariant.
            fixture.clients.first.clear()

            fixture.crypto.first.messenger.send(
                DecodedPeerMessage.Complete(
                    TransferComplete(
                        transferIdHash = fixture.transferHash,
                        fileIdHash = fixture.fileHash,
                        totalChunks = 2,
                    ),
                ),
            )

            assertFailsWith<IllegalArgumentException> {
                fixture.incoming.dispatch(fixture.clients.second.removeFirst())
            }
            assertEquals(IncomingTransferState.ACCEPTED, fixture.incoming.state)
            assertNull(fixture.receiver.getProgress(fixture.transferId).value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `COMPLETE chunk count different from OFFER is rejected`() = runTest {
        val fixture = fixture(chunkSize = 8, offerSize = 8, offerChunks = 1)
        try {
            fixture.incoming.accept()
            fixture.clients.first.clear()

            // First complete the only local DATA chunk so the mismatch check is isolated.
            fixture.crypto.first.messenger.send(
                DecodedPeerMessage.Data(
                    TransferData(
                        transferIdHash = fixture.transferHash,
                        fileIdHash = fixture.fileHash,
                        chunkIndex = 0,
                        totalChunks = 1,
                        ciphertext = ByteArray(8) { 7 },
                        nonce = ByteArray(12) { 1 },
                    ),
                ),
            )
            val data = fixture.incoming.dispatch(fixture.clients.second.removeFirst())
            assertIs<IncomingDispatchResult.Data>(data)

            fixture.crypto.first.messenger.send(
                DecodedPeerMessage.Complete(
                    TransferComplete(
                        transferIdHash = fixture.transferHash,
                        fileIdHash = fixture.fileHash,
                        totalChunks = 2,
                    ),
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                fixture.incoming.dispatch(fixture.clients.second.removeFirst())
            }
            assertEquals(IncomingTransferState.RECEIVING, fixture.incoming.state)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `remote CANCEL after partial DATA releases receiver state`() = runTest {
        val fixture = fixture(chunkSize = 8)
        try {
            fixture.incoming.accept()
            fixture.clients.first.clear()

            fixture.crypto.first.messenger.send(
                DecodedPeerMessage.Data(
                    TransferData(
                        transferIdHash = fixture.transferHash,
                        fileIdHash = fixture.fileHash,
                        chunkIndex = 0,
                        totalChunks = 2,
                        ciphertext = ByteArray(8) { 9 },
                        nonce = ByteArray(12) { 2 },
                    ),
                ),
            )
            assertIs<IncomingDispatchResult.Data>(
                fixture.incoming.dispatch(fixture.clients.second.removeFirst()),
            )
            assertTrue(fixture.receiver.getProgress(fixture.transferId).value != null)

            fixture.crypto.first.messenger.send(
                DecodedPeerMessage.Cancel(
                    TransferCancel(fixture.transferHash, "sender cancelled"),
                ),
            )
            val cancelled = assertIs<IncomingDispatchResult.RemoteCancel>(
                fixture.incoming.dispatch(fixture.clients.second.removeFirst()),
            )
            assertTrue(cancelled.released)
            assertEquals(IncomingTransferState.CANCELLED, fixture.incoming.state)
            assertNull(fixture.receiver.getProgress(fixture.transferId).value)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(
        chunkSize: Int,
        offerSize: Long = 16,
        offerChunks: Int = 2,
    ): Fixture {
        val production = DesktopProductionCrypto.create()
        val sessionId = SessionId("completion-security-session")
        val sessions = establishedPair(sessionId)
        val clients = clientPair()
        val transferId = TransferId("completion-security-transfer")
        val fileId = FileId("completion-security-file")
        val senderCrypto = EstablishedTransferCrypto.open(
            sessions.first,
            EstablishedSessionSide.INITIATOR,
            transferId,
            clients.first,
            production.cipher,
            production.random,
        )
        val receiverCrypto = EstablishedTransferCrypto.open(
            sessions.second,
            EstablishedSessionSide.RESPONDER,
            transferId,
            clients.second,
            production.cipher,
            production.random,
        )
        val config = TransferConfig(
            chunkSize = chunkSize,
            maxCiphertextSize = chunkSize + TransferProtocol.AEAD_OVERHEAD_ALLOWANCE,
            maxTransferBytes = 1_024,
        )
        val receiver = InMemoryTransferReceiver(IdentityDecryptor(), config)
        val offer = TransferOffer(
            fileId = fileId,
            displayName = "completion.bin",
            sizeBytes = offerSize,
            totalChunks = offerChunks,
        )
        val incoming = IncomingSharingTransfer(
            session = sessions.second,
            transferId = transferId,
            offer = offer,
            crypto = receiverCrypto,
            receiver = receiver,
            config = config,
        )
        return Fixture(
            sessions = sessions,
            clients = clients,
            crypto = senderCrypto to receiverCrypto,
            transferId = transferId,
            fileId = fileId,
            transferHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray()),
            fileHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray()),
            receiver = receiver,
            incoming = incoming,
        )
    }

    private class IdentityDecryptor : TransferDecryptor {
        override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray = ciphertext.copyOf()
        override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray = ciphertext.copyOf()
    }

    private data class Fixture(
        val sessions: Pair<EstablishedPeerSession, EstablishedPeerSession>,
        val clients: Pair<LoopbackClient, LoopbackClient>,
        val crypto: Pair<EstablishedTransferCrypto, EstablishedTransferCrypto>,
        val transferId: TransferId,
        val fileId: FileId,
        val transferHash: String,
        val fileHash: String,
        val receiver: InMemoryTransferReceiver,
        val incoming: IncomingSharingTransfer,
    ) {
        fun close() {
            crypto.first.close()
            crypto.second.close()
            sessions.first.close()
            sessions.second.close()
        }
    }

    private fun establishedPair(sessionId: SessionId): Pair<EstablishedPeerSession, EstablishedPeerSession> =
        established(sessionId, 1, 2, "2345-6789-ABCD-EFGH", "JKLM-NPQR-STUV-WXYZ") to
            established(sessionId, 2, 1, "JKLM-NPQR-STUV-WXYZ", "2345-6789-ABCD-EFGH")

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
            transcriptHash = "completion-security-transcript",
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
        override val incoming: Flow<SignalingEnvelope> = MutableSharedFlow()
        private val queue = ArrayDeque<SignalingEnvelope>()
        private var counter = 0
        lateinit var peer: LoopbackClient
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
        fun clear() = queue.clear()
    }
}
