package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.ContactVerificationMethod
import dev.veilshare.core.contacts.InMemoryTrustedContactStore
import dev.veilshare.core.contacts.LookupTrustResolver
import dev.veilshare.core.contacts.PeerIdentityCandidate
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.JvmHkdfSha256KeyDeriver
import dev.veilshare.core.crypto.JvmX25519KeyAgreement
import dev.veilshare.core.crypto.toBase64
import dev.veilshare.core.identity.InMemorySharingIdentityStore
import dev.veilshare.core.identity.InMemorySharingPresenceStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
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

class TrustedSharingFullFlowE2ETest {
    private val signer = JvmEd25519Signer()
    private val handshake = JvmHandshakeProtocol()
    private val keyAgreement = JvmX25519KeyAgreement()
    private val keyDeriver = JvmHkdfSha256KeyDeriver()

    @Test
    fun `verified contacts establish session and transfer file into durable vault`() = runTest {
        val senderContext = SharingContextId("golden-sender-context")
        val receiverContext = SharingContextId("golden-receiver-context")
        val senderIdentities = identityManager(10)
        val receiverIdentities = identityManager(20)
        val senderPresence = presenceManager(30)
        val receiverPresence = presenceManager(40)

        val senderPublic = senderIdentities.getOrCreate(senderContext).usePublic()
        val receiverPublic = receiverIdentities.getOrCreate(receiverContext).usePublic()
        val senderRoute = senderPresence.getOrCreate(senderContext).referenceCode
        val receiverRoute = receiverPresence.getOrCreate(receiverContext).referenceCode

        val senderContacts = contactManager(50)
        val receiverContacts = contactManager(60)
        senderContacts.addVerified(
            alias = "Receiver",
            candidate = PeerIdentityCandidate(receiverPublic.identityId, receiverPublic.publicKey, receiverRoute),
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )
        receiverContacts.addVerified(
            alias = "Sender",
            candidate = PeerIdentityCandidate(senderPublic.identityId, senderPublic.publicKey, senderRoute),
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )

        // Phase 1: trusted authenticated handshake.
        val senderHandshakeSignal = CapturingSignalingClient(
            LookupResponse(
                status = LookupStatus.FOUND,
                sharingIdentityId = receiverPublic.identityId,
                sharingPublicKey = receiverPublic.publicKey.bytes.toBase64(),
            ),
        )
        val receiverHandshakeSignal = CapturingSignalingClient(LookupResponse(LookupStatus.NOT_FOUND))

        val started = assertIs<OutboundSessionStartResult.Started>(
            TrustedOutboundSessionStarter(
                signalingClient = senderHandshakeSignal,
                identities = senderIdentities,
                presence = senderPresence,
                trustResolver = LookupTrustResolver(senderContacts),
                handshake = handshake,
                signer = signer,
                random = CountingRandom(70),
            ).start(senderContext, receiverRoute),
        )

        val routedHello = decodeHandshakeRelay(senderHandshakeSignal.relays.single(), "golden-hello")
        val inbound = assertIs<InboundSessionBeginResult.Pending>(
            TrustedInboundSessionResponder(
                signalingClient = receiverHandshakeSignal,
                identities = receiverIdentities,
                presence = receiverPresence,
                helloVerifier = TrustedInboundHelloVerifier(receiverContacts, handshake, signer),
                handshake = handshake,
                signer = signer,
                keyAgreement = keyAgreement,
                keyDeriver = keyDeriver,
            ).begin(receiverContext, started.sessionId, routedHello),
        )

        val routedConfirm = decodeHandshakeRelay(receiverHandshakeSignal.relays.single(), "golden-confirm")
        val outboundCompletion = TrustedOutboundHandshakeCompleter(
            signalingClient = senderHandshakeSignal,
            identities = senderIdentities,
            handshake = handshake,
            signer = signer,
            keyAgreement = keyAgreement,
            keyDeriver = keyDeriver,
        ).complete(started, routedConfirm)

        val routedAck = decodeHandshakeRelay(senderHandshakeSignal.relays.last(), "golden-ack")
        val ack = assertIs<DecodedPeerMessage.ConfirmAck>(routedAck.message).value
        val senderSession = outboundCompletion.session
        val receiverSession = inbound.handshake.complete(ack)

        assertContentEquals(
            senderSession.keys.senderToReceiverDataKey,
            receiverSession.keys.senderToReceiverDataKey,
        )
        assertContentEquals(
            senderSession.keys.receiverToSenderDataKey,
            receiverSession.keys.receiverToSenderDataKey,
        )
        assertContentEquals(
            senderSession.keys.senderToReceiverEnvelopeKey,
            receiverSession.keys.senderToReceiverEnvelopeKey,
        )
        assertContentEquals(
            senderSession.keys.receiverToSenderEnvelopeKey,
            receiverSession.keys.receiverToSenderEnvelopeKey,
        )

        // Phase 2: same derived session keys protect metadata + DATA and durable import.
        val production = DesktopProductionCrypto.create()
        val senderTransferSignal = LoopbackSignalingClient("sender")
        val receiverTransferSignal = LoopbackSignalingClient("receiver")
        senderTransferSignal.peer = receiverTransferSignal
        receiverTransferSignal.peer = senderTransferSignal

        val transferId = TransferId("golden-transfer")
        val fileId = FileId("golden-file")
        val senderCrypto = EstablishedTransferCrypto.open(
            session = senderSession,
            side = EstablishedSessionSide.INITIATOR,
            transferId = transferId,
            signalingClient = senderTransferSignal,
            cipher = production.cipher,
            random = production.random,
        )
        val receiverCrypto = EstablishedTransferCrypto.open(
            session = receiverSession,
            side = EstablishedSessionSide.RESPONDER,
            transferId = transferId,
            signalingClient = receiverTransferSignal,
            cipher = production.cipher,
            random = production.random,
        )
        val plaintext = ByteArray(1_048_576 + 65_537) { index -> ((index * 29 + 17) and 0xff).toByte() }
        val root = Files.createTempDirectory("veilshare-golden-flow-")

        try {
            val outgoing = OutgoingSharingTransfer(
                session = senderSession,
                transferId = transferId,
                fileId = fileId,
                source = ByteArrayTransferSource(
                    data = plaintext,
                    displayName = "golden-secret.bin",
                    mimeHint = "application/octet-stream",
                ),
                crypto = senderCrypto,
                sender = DefaultTransferSender(production.random),
            )

            val offer = outgoing.sendOffer()
            assertEquals(2, offer.totalChunks)
            val incomingOffer = decodeIncomingTransferOffer(
                envelope = receiverTransferSignal.removeFirst(),
                session = receiverSession,
                crypto = receiverCrypto,
            )
            assertEquals(transferId, incomingOffer.transferId)
            assertEquals(offer, incomingOffer.offer)

            val receiver = InMemoryTransferReceiver(receiverCrypto.decryptor)
            val incoming = IncomingSharingTransfer(
                session = receiverSession,
                transferId = transferId,
                offer = incomingOffer.offer,
                crypto = receiverCrypto,
                receiver = receiver,
            )
            incoming.accept()
            assertIs<OutgoingControlResult.Accepted>(
                outgoing.handleControl(senderTransferSignal.removeFirst()),
            )

            val sent = outgoing.sendAccepted()
            assertEquals(2, sent.totalChunks)
            assertEquals(plaintext.size.toLong(), sent.totalBytes)

            var ready = false
            while (receiverTransferSignal.hasPending()) {
                if (incoming.dispatch(receiverTransferSignal.removeFirst()) is IncomingDispatchResult.ReadyToImport) {
                    ready = true
                }
            }
            assertTrue(ready)
            assertEquals(IncomingTransferState.READY_TO_IMPORT, incoming.state)

            val service = DesktopLocalVaultService(root)
            service.createPair("8411".toCharArray(), "8412".toCharArray())
            val vault = assertIs<LocalUnlockResult.Ready>(service.unlock("8411".toCharArray())).vault
            val imported = incoming.importIntoVault(vault)
            assertEquals("golden-secret.bin", imported.displayName)
            assertContentEquals(plaintext, readAll(vault, imported))
            vault.close()

            val reopened = assertIs<LocalUnlockResult.Ready>(
                DesktopLocalVaultService(root).unlock("8411".toCharArray()),
            ).vault
            val durable = assertIs<VaultItem.File>(reopened.find(imported.id))
            assertContentEquals(plaintext, readAll(reopened, durable))
            reopened.close()
        } finally {
            senderCrypto.close()
            receiverCrypto.close()
            senderSession.close()
            receiverSession.close()
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }

        assertTrue(senderSession.keys.senderToReceiverDataKey.all { it == 0.toByte() })
        assertTrue(receiverSession.keys.senderToReceiverDataKey.all { it == 0.toByte() })
    }

    private fun decodeHandshakeRelay(relay: RelayRequest, id: String): RoutedHandshakeMessage =
        HandshakeSignalingInbox().decodeRoutedRelay(
            SignalingEnvelope(
                protocolVersion = SharingProtocol.VERSION,
                messageId = MessageId(id),
                type = MessageType.RELAY,
                sessionId = relay.sessionId,
                payload = relay.opaquePayload,
            ),
        )

    private fun identityManager(seed: Int) = SharingIdentityManager(
        InMemorySharingIdentityStore(),
        CountingRandom(seed),
        signer,
    )

    private fun presenceManager(seed: Int) = SharingPresenceManager(
        InMemorySharingPresenceStore(),
        CountingRandom(seed),
    )

    private fun contactManager(seed: Int) = TrustedContactManager(
        InMemoryTrustedContactStore(),
        CountingRandom(seed),
    )

    private fun dev.veilshare.core.identity.SharingIdentityHandle.usePublic() =
        try {
            publicIdentity
        } finally {
            close()
        }

    private suspend fun readAll(
        vault: dev.veilshare.core.vault.VaultHandle,
        item: VaultItem.File,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        vault.readFile(item.id) { output.write(it) }
        return output.toByteArray()
    }

    private class CapturingSignalingClient(
        private val response: LookupResponse,
    ) : SignalingClient {
        override val incoming: Flow<SignalingEnvelope> = MutableSharedFlow()
        val relays = mutableListOf<RelayRequest>()
        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun unregister(request: UnregisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse = response
        override suspend fun relay(request: RelayRequest) {
            relays += request
        }
        override suspend fun close() = Unit
    }

    private class LoopbackSignalingClient(
        private val label: String,
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
                    messageId = MessageId("$label-${counter++}"),
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

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var counter = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 37 + index) and 0xff).toByte() }
        }
    }
}
