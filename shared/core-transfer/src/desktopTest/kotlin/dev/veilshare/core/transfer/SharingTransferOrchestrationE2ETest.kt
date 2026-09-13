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
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import dev.veilshare.core.vault.DesktopLocalVaultService
import dev.veilshare.core.vault.LocalUnlockResult
import dev.veilshare.core.vault.VaultItem
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharingTransferOrchestrationE2ETest {
    @Test
    fun `established session offer accept data complete imports durable vault`() = runTest {
        val production = DesktopProductionCrypto.create()
        val sessionId = SessionId("session-transfer-e2e")
        val clientA = LoopbackSignalingClient("a")
        val clientB = LoopbackSignalingClient("b")
        clientA.peer = clientB
        clientB.peer = clientA

        val sessionA = establishedSession(
            sessionId = sessionId,
            localMarker = 10,
            peerMarker = 20,
            localCode = "2345-6789-ABCD-EFGH",
            peerCode = "JKLM-NPQR-STUV-WXYZ",
        )
        val sessionB = establishedSession(
            sessionId = sessionId,
            localMarker = 20,
            peerMarker = 10,
            localCode = "JKLM-NPQR-STUV-WXYZ",
            peerCode = "2345-6789-ABCD-EFGH",
        )

        val transferId = TransferId("transfer-e2e-established")
        val fileId = FileId("file-e2e-established")
        val plaintext = ByteArray(1_048_576 + 12_345) { index -> ((index * 17 + 43) and 0xff).toByte() }
        val config = TransferConfig()
        val cryptoA = EstablishedTransferCrypto.open(
            session = sessionA,
            side = EstablishedSessionSide.INITIATOR,
            transferId = transferId,
            signalingClient = clientA,
            cipher = production.cipher,
            random = production.random,
        )
        val cryptoB = EstablishedTransferCrypto.open(
            session = sessionB,
            side = EstablishedSessionSide.RESPONDER,
            transferId = transferId,
            signalingClient = clientB,
            cipher = production.cipher,
            random = production.random,
        )
        val root = Files.createTempDirectory("veilshare-orchestration-e2e-")

        try {
            val outgoing = OutgoingSharingTransfer(
                session = sessionA,
                transferId = transferId,
                fileId = fileId,
                source = ByteArrayTransferSource(
                    data = plaintext,
                    displayName = "orchestrated.bin",
                    mimeHint = "application/octet-stream",
                ),
                crypto = cryptoA,
                sender = DefaultTransferSender(production.random, config),
                config = config,
            )

            val sentOffer = outgoing.sendOffer()
            assertEquals(2, sentOffer.totalChunks)
            assertEquals(OutgoingTransferState.OFFERED, outgoing.state)

            val decodedOffer = decodeIncomingTransferOffer(
                envelope = clientB.removeFirst(),
                session = sessionB,
                crypto = cryptoB,
                config = config,
            )
            assertEquals(transferId, decodedOffer.transferId)
            assertEquals(sentOffer, decodedOffer.offer)

            val receiver = InMemoryTransferReceiver(cryptoB.decryptor, config)
            val incoming = IncomingSharingTransfer(
                session = sessionB,
                transferId = decodedOffer.transferId,
                offer = decodedOffer.offer,
                crypto = cryptoB,
                receiver = receiver,
                config = config,
            )
            incoming.accept()
            assertEquals(IncomingTransferState.ACCEPTED, incoming.state)

            assertIs<OutgoingControlResult.Accepted>(
                outgoing.handleControl(clientA.removeFirst()),
            )
            assertEquals(OutgoingTransferState.ACCEPTED, outgoing.state)

            val sent = outgoing.sendAccepted()
            assertEquals(2, sent.totalChunks)
            assertEquals(plaintext.size.toLong(), sent.totalBytes)
            assertEquals(OutgoingTransferState.COMPLETE, outgoing.state)

            var ready = false
            while (clientB.hasPending()) {
                when (incoming.dispatch(clientB.removeFirst())) {
                    IncomingDispatchResult.ReadyToImport -> ready = true
                    else -> Unit
                }
            }
            assertTrue(ready)
            assertEquals(IncomingTransferState.READY_TO_IMPORT, incoming.state)

            val service = DesktopLocalVaultService(root)
            service.createPair("7311".toCharArray(), "7312".toCharArray())
            val vault = assertIs<LocalUnlockResult.Ready>(service.unlock("7311".toCharArray())).vault
            val imported = incoming.importIntoVault(vault)
            assertEquals(IncomingTransferState.COMPLETE, incoming.state)
            assertEquals("orchestrated.bin", imported.displayName)
            assertContentEquals(plaintext, readAll(vault, imported))
            vault.close()

            val reopened = assertIs<LocalUnlockResult.Ready>(
                DesktopLocalVaultService(root).unlock("7311".toCharArray()),
            ).vault
            val durable = assertIs<VaultItem.File>(reopened.find(imported.id))
            assertContentEquals(plaintext, readAll(reopened, durable))
            reopened.close()
        } finally {
            cryptoA.close()
            cryptoB.close()
            sessionA.close()
            sessionB.close()
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    private fun establishedSession(
        sessionId: SessionId,
        localMarker: Int,
        peerMarker: Int,
        localCode: String,
        peerCode: String,
    ): EstablishedPeerSession {
        val localId = SharingIdentityId("identity-$localMarker")
        val peerId = SharingIdentityId("identity-$peerMarker")
        val localKey = Ed25519PublicKey(ByteArray(32) { ((localMarker * 11 + it) and 0xff).toByte() })
        val peerKey = Ed25519PublicKey(ByteArray(32) { ((peerMarker * 11 + it) and 0xff).toByte() })
        return EstablishedPeerSession(
            sessionId = sessionId,
            peer = PinnedPeerIdentity(
                contactId = ContactId("contact-$peerMarker"),
                sharingIdentityId = peerId,
                sharingIdentityIdHash = "peer-hash-$peerMarker",
                publicKey = peerKey,
                fingerprint = Fingerprint("peer-fingerprint-$peerMarker"),
            ),
            localIdentity = SharingPublicIdentity(
                contextId = SharingContextId("context-$localMarker"),
                identityId = localId,
                publicKey = localKey,
                fingerprint = Fingerprint("local-fingerprint-$localMarker"),
            ),
            localReferenceCode = ReferenceCodes.parse(localCode),
            peerReferenceCode = ReferenceCodes.parse(peerCode),
            keys = matchingKeys(),
        )
    }

    private fun matchingKeys(): HandshakeKeys = HandshakeKeys(
        senderToReceiverDataKey = ByteArray(32) { ((31 + it) and 0xff).toByte() },
        receiverToSenderDataKey = ByteArray(32) { ((67 + it) and 0xff).toByte() },
        senderToReceiverEnvelopeKey = ByteArray(32) { ((103 + it) and 0xff).toByte() },
        receiverToSenderEnvelopeKey = ByteArray(32) { ((149 + it) and 0xff).toByte() },
        transcriptHash = "transfer-orchestration-transcript",
    )

    private suspend fun readAll(
        vault: dev.veilshare.core.vault.VaultHandle,
        item: VaultItem.File,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        vault.readFile(item.id) { output.write(it) }
        return output.toByteArray()
    }

    private class LoopbackSignalingClient(
        private val name: String,
    ) : SignalingClient {
        private val events = MutableSharedFlow<SignalingEnvelope>()
        private val queue = ArrayDeque<SignalingEnvelope>()
        private var counter = 0
        lateinit var peer: LoopbackSignalingClient

        override val incoming: Flow<SignalingEnvelope> = events
        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun unregister(request: UnregisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse = LookupResponse(LookupStatus.NOT_FOUND)

        override suspend fun relay(request: RelayRequest) {
            peer.queue.addLast(
                SignalingEnvelope(
                    protocolVersion = SharingProtocol.VERSION,
                    messageId = MessageId("$name-${counter++}"),
                    type = MessageType.RELAY,
                    sessionId = request.sessionId,
                    payload = request.opaquePayload.copyOf(),
                ),
            )
        }

        override suspend fun close() = Unit

        fun hasPending(): Boolean = queue.isNotEmpty()
        fun removeFirst(): SignalingEnvelope = queue.removeFirst()
    }
}
