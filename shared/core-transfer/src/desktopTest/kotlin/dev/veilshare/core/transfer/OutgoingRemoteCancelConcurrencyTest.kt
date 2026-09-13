package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.PinnedPeerIdentity
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.HandshakeKeys
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
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OutgoingRemoteCancelConcurrencyTest {
    @Test
    fun `remote cancel interrupts sender before next transport frame`() = runTest {
        val production = DesktopProductionCrypto.create()
        val sessions = sessionPair()
        val clients = clientPair()
        val transferId = TransferId("remote-cancel-transfer")
        val fileId = FileId("remote-cancel-file")
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
        val firstFrameSent = CompletableDeferred<Unit>()
        val continueSending = CompletableDeferred<Unit>()
        val source = CountingSource()
        val scriptedSender = ScriptedTransferSender(firstFrameSent, continueSending)
        val outgoing = OutgoingSharingTransfer(
            session = sessions.first,
            transferId = transferId,
            fileId = fileId,
            source = source,
            crypto = senderCrypto,
            sender = scriptedSender,
            config = TransferConfig(chunkSize = 8, maxTransferBytes = 64),
        )

        try {
            outgoing.sendOffer()
            // Drop the OFFER from the receiver queue and send a valid ACCEPT back.
            clients.second.removeFirst()
            receiverCrypto.messenger.send(
                DecodedPeerMessage.Accept(dev.veilshare.core.model.TransferAccept(fileId)),
            )
            assertIs<OutgoingControlResult.Accepted>(
                outgoing.handleControl(clients.first.removeFirst()),
            )

            val sending = async { outgoing.sendAccepted() }
            firstFrameSent.await()
            assertEquals(1, clients.second.pendingCount())

            val hash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
            receiverCrypto.messenger.send(
                DecodedPeerMessage.Cancel(TransferCancel(hash, "receiver stopped")),
            )
            assertIs<OutgoingControlResult.RemoteCancel>(
                outgoing.handleControl(clients.first.removeFirst()),
            )
            assertEquals(OutgoingTransferState.CANCELLED, outgoing.state)

            continueSending.complete(Unit)
            assertFailsWith<CancellationException> { sending.await() }

            // The scripted second DATA frame never reaches the peer after cancellation.
            assertEquals(1, clients.second.pendingCount())
            assertEquals(1, source.closeCalls)
            assertEquals(OutgoingTransferState.CANCELLED, outgoing.state)
        } finally {
            senderCrypto.close()
            receiverCrypto.close()
            sessions.first.close()
            sessions.second.close()
        }
    }

    private class ScriptedTransferSender(
        private val firstFrameSent: CompletableDeferred<Unit>,
        private val continueSending: CompletableDeferred<Unit>,
    ) : TransferSender {
        override suspend fun send(
            transferId: TransferId,
            fileId: FileId,
            source: TransferSource,
            encryptor: TransferEncryptor,
            sender: TransferNetworkSender,
        ): TransferResult {
            val transferHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
            val fileHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray())
            return try {
                sender.send(
                    TransferData(
                        transferIdHash = transferHash,
                        fileIdHash = fileHash,
                        chunkIndex = 0,
                        totalChunks = 1,
                        ciphertext = ByteArray(4) { 1 },
                        nonce = ByteArray(12) { 2 },
                        fragmentIndex = 0,
                        fragmentCount = 2,
                    ),
                )
                firstFrameSent.complete(Unit)
                continueSending.await()
                sender.send(
                    TransferData(
                        transferIdHash = transferHash,
                        fileIdHash = fileHash,
                        chunkIndex = 0,
                        totalChunks = 1,
                        ciphertext = ByteArray(4) { 3 },
                        nonce = ByteArray(12) { 2 },
                        fragmentIndex = 1,
                        fragmentCount = 2,
                    ),
                )
                sender.complete(transferHash, fileHash, 1)
                TransferResult(1, 8)
            } finally {
                source.close()
            }
        }
    }

    private class CountingSource : TransferSource {
        var closeCalls = 0
        override val fileSize: Long = 8
        override val displayName: String = "cancel.bin"
        override val mimeHint: String? = "application/octet-stream"
        override suspend fun readChunk(offset: Long, size: Int): ByteArray = ByteArray(size)
        override suspend fun close() {
            closeCalls++
        }
    }

    private fun sessionPair(): Pair<EstablishedPeerSession, EstablishedPeerSession> {
        val id = SessionId("remote-cancel-session")
        return established(id, 1, 2, "2345-6789-ABCD-EFGH", "JKLM-NPQR-STUV-WXYZ") to
            established(id, 2, 1, "JKLM-NPQR-STUV-WXYZ", "2345-6789-ABCD-EFGH")
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
            transcriptHash = "remote-cancel-transcript",
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
        fun pendingCount(): Int = queue.size
    }
}
