package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.ContactVerificationMethod
import dev.veilshare.core.contacts.InMemoryTrustedContactStore
import dev.veilshare.core.contacts.LookupTrustResolver
import dev.veilshare.core.contacts.PeerIdentityCandidate
import dev.veilshare.core.contacts.TrustedContactManager
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
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustedHandshakeOrchestrationE2ETest {
    private val signer = JvmEd25519Signer()
    private val handshake = JvmHandshakeProtocol()
    private val keyAgreement = JvmX25519KeyAgreement()
    private val keyDeriver = JvmHkdfSha256KeyDeriver()

    @Test
    fun `trusted peers establish identical directional session keys`() = runTest {
        val senderContext = SharingContextId("sender-context")
        val receiverContext = SharingContextId("receiver-context")
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

        val senderSignal = CapturingSignalingClient(
            LookupResponse(
                status = LookupStatus.FOUND,
                sharingIdentityId = receiverPublic.identityId,
                sharingPublicKey = receiverPublic.publicKey.bytes.toBase64(),
            ),
        )
        val receiverSignal = CapturingSignalingClient(LookupResponse(status = LookupStatus.NOT_FOUND))

        val started = assertIs<OutboundSessionStartResult.Started>(
            TrustedOutboundSessionStarter(
                signalingClient = senderSignal,
                identities = senderIdentities,
                presence = senderPresence,
                trustResolver = LookupTrustResolver(senderContacts),
                handshake = handshake,
                signer = signer,
                random = CountingRandom(70),
            ).start(senderContext, receiverRoute),
        )

        val routedHello = decodeRelay(senderSignal.relays.single(), "hello")
        assertEquals(senderRoute, routedHello.replyReferenceCode)

        val inbound = assertIs<InboundSessionBeginResult.Pending>(
            TrustedInboundSessionResponder(
                signalingClient = receiverSignal,
                identities = receiverIdentities,
                presence = receiverPresence,
                helloVerifier = TrustedInboundHelloVerifier(receiverContacts, handshake, signer),
                handshake = handshake,
                signer = signer,
                keyAgreement = keyAgreement,
                keyDeriver = keyDeriver,
            ).begin(receiverContext, started.sessionId, routedHello),
        )

        val routedConfirm = decodeRelay(receiverSignal.relays.single(), "confirm")
        assertEquals(receiverRoute, routedConfirm.replyReferenceCode)

        val outboundCompletion = TrustedOutboundHandshakeCompleter(
            signalingClient = senderSignal,
            identities = senderIdentities,
            handshake = handshake,
            signer = signer,
            keyAgreement = keyAgreement,
            keyDeriver = keyDeriver,
        ).complete(started, routedConfirm)

        val ackRelay = senderSignal.relays.last()
        val routedAck = decodeRelay(ackRelay, "ack")
        val ack = assertIs<DecodedPeerMessage.ConfirmAck>(routedAck.message).value
        val inboundSession = inbound.handshake.complete(ack)
        val outboundSession = outboundCompletion.session

        try {
            assertEquals(started.sessionId, outboundSession.sessionId)
            assertEquals(started.sessionId, inboundSession.sessionId)
            assertEquals(receiverRoute, outboundSession.peerReferenceCode)
            assertEquals(senderRoute, inboundSession.peerReferenceCode)

            assertContentEquals(
                outboundSession.keys.senderToReceiverDataKey,
                inboundSession.keys.senderToReceiverDataKey,
            )
            assertContentEquals(
                outboundSession.keys.receiverToSenderDataKey,
                inboundSession.keys.receiverToSenderDataKey,
            )
            assertContentEquals(
                outboundSession.keys.senderToReceiverEnvelopeKey,
                inboundSession.keys.senderToReceiverEnvelopeKey,
            )
            assertContentEquals(
                outboundSession.keys.receiverToSenderEnvelopeKey,
                inboundSession.keys.receiverToSenderEnvelopeKey,
            )
            assertEquals(outboundSession.keys.transcriptHash, inboundSession.keys.transcriptHash)
        } finally {
            outboundSession.close()
            inboundSession.close()
        }

        assertTrue(outboundSession.keys.senderToReceiverDataKey.all { it == 0.toByte() })
        assertTrue(inboundSession.keys.senderToReceiverDataKey.all { it == 0.toByte() })
    }

    @Test
    fun `local identity rotation during outbound handshake invalidates pending session`() = runTest {
        val senderContext = SharingContextId("sender-context")
        val receiverContext = SharingContextId("receiver-context")
        val senderIdentities = identityManager(100)
        val receiverIdentities = identityManager(110)
        val senderPresence = presenceManager(120)
        val receiverPresence = presenceManager(130)
        val senderPublic = senderIdentities.getOrCreate(senderContext).usePublic()
        val receiverPublic = receiverIdentities.getOrCreate(receiverContext).usePublic()
        val senderRoute = senderPresence.getOrCreate(senderContext).referenceCode
        val receiverRoute = receiverPresence.getOrCreate(receiverContext).referenceCode
        val senderContacts = contactManager(140)
        val receiverContacts = contactManager(150)
        senderContacts.addVerified(
            "Receiver",
            PeerIdentityCandidate(receiverPublic.identityId, receiverPublic.publicKey, receiverRoute),
            ContactVerificationMethod.QR_CODE,
        )
        receiverContacts.addVerified(
            "Sender",
            PeerIdentityCandidate(senderPublic.identityId, senderPublic.publicKey, senderRoute),
            ContactVerificationMethod.QR_CODE,
        )

        val senderSignal = CapturingSignalingClient(
            LookupResponse(
                LookupStatus.FOUND,
                receiverPublic.identityId,
                receiverPublic.publicKey.bytes.toBase64(),
            ),
        )
        val receiverSignal = CapturingSignalingClient(LookupResponse(LookupStatus.NOT_FOUND))
        val started = assertIs<OutboundSessionStartResult.Started>(
            TrustedOutboundSessionStarter(
                senderSignal,
                senderIdentities,
                senderPresence,
                LookupTrustResolver(senderContacts),
                handshake,
                signer,
                CountingRandom(160),
            ).start(senderContext, receiverRoute),
        )
        val inbound = assertIs<InboundSessionBeginResult.Pending>(
            TrustedInboundSessionResponder(
                receiverSignal,
                receiverIdentities,
                receiverPresence,
                TrustedInboundHelloVerifier(receiverContacts, handshake, signer),
                handshake,
                signer,
                keyAgreement,
                keyDeriver,
            ).begin(receiverContext, started.sessionId, decodeRelay(senderSignal.relays.single(), "hello")),
        )
        val routedConfirm = decodeRelay(receiverSignal.relays.single(), "confirm")

        senderIdentities.rotate(senderContext).close()

        assertFailsWith<IllegalArgumentException> {
            TrustedOutboundHandshakeCompleter(
                senderSignal,
                senderIdentities,
                handshake,
                signer,
                keyAgreement,
                keyDeriver,
            ).complete(started, routedConfirm)
        }
        inbound.handshake.close()
        assertEquals(1, senderSignal.relays.size)
    }

    private fun decodeRelay(relay: RelayRequest, id: String): RoutedHandshakeMessage =
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

    private class CapturingSignalingClient(
        private val response: LookupResponse,
    ) : SignalingClient {
        override val incoming: Flow<SignalingEnvelope> = MutableSharedFlow()
        val relays = mutableListOf<RelayRequest>()

        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse = response
        override suspend fun relay(request: RelayRequest) {
            relays += request
        }
        override suspend fun close() = Unit
    }

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var counter = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 37 + index) and 0xff).toByte() }
        }
    }
}
