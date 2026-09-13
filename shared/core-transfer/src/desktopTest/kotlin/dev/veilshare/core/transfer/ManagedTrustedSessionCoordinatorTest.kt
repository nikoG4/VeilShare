package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.ContactVerificationMethod
import dev.veilshare.core.contacts.InMemoryTrustedContactStore
import dev.veilshare.core.contacts.PeerIdentityCandidate
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.JvmHkdfSha256KeyDeriver
import dev.veilshare.core.crypto.JvmX25519KeyAgreement
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.identity.InMemorySharingIdentityStore
import dev.veilshare.core.identity.InMemorySharingPresenceStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.identity.SharingPublicIdentity
import dev.veilshare.core.model.ContactId
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedTrustedSessionCoordinatorTest {
    private val signer = JvmEd25519Signer()
    private val handshake = JvmHandshakeProtocol()
    private val agreement = JvmX25519KeyAgreement()
    private val deriver = JvmHkdfSha256KeyDeriver()

    @Test
    fun `full pending registry rejects before sending SESSION_CONFIRM`() = runTest {
        val clock = MutableClock()
        val registry = TrustedSessionRegistry(clock = clock, maxPending = 1)
        val occupied = pending(SessionId("occupied-session"))
        assertIs<PendingRegistrationResult.Registered>(registry.registerPending(occupied))

        val localContext = SharingContextId("receiver-context")
        val localIdentities = identityManager(10)
        val localPresence = presenceManager(20)
        val senderKeys = signer.generateKeyPair()
        val senderId = SharingIdentityId("trusted-sender")
        val senderRoute = ReferenceCodes.parse("2345-6789-ABCD-EFGH")
        val contacts = TrustedContactManager(InMemoryTrustedContactStore(), CountingRandom(30))
        contacts.addVerified(
            alias = "Sender",
            candidate = PeerIdentityCandidate(senderId, senderKeys.publicKey, senderRoute),
            verificationMethod = ContactVerificationMethod.QR_CODE,
        )
        val signaling = CapturingClient()
        val responder = TrustedInboundSessionResponder(
            signalingClient = signaling,
            identities = localIdentities,
            presence = localPresence,
            helloVerifier = TrustedInboundHelloVerifier(contacts, handshake, signer),
            handshake = handshake,
            signer = signer,
            keyAgreement = agreement,
            keyDeriver = deriver,
        )
        val coordinator = ManagedTrustedSessionCoordinator(responder, registry)
        val sessionId = SessionId("new-session")
        val hello = handshake.createSessionHello(senderId, senderKeys, sessionId, signer)

        try {
            val result = coordinator.beginInbound(
                localContextId = localContext,
                sessionId = sessionId,
                routedHello = RoutedHandshakeMessage(
                    message = DecodedPeerMessage.Hello(hello),
                    replyReferenceCode = senderRoute,
                ),
            )
            val capacity = assertIs<ManagedInboundBeginResult.RegistryCapacityRejected>(result)
            assertEquals(1, capacity.maxPending)
            assertTrue(signaling.relays.isEmpty())
            assertEquals(1, registry.pendingCount())
        } finally {
            senderKeys.privateKey.material.close()
            registry.shutdown()
        }
    }

    @Test
    fun `duplicate session id rejects before responder side effects`() = runTest {
        val registry = TrustedSessionRegistry(maxPending = 2)
        val duplicateId = SessionId("duplicate-managed-session")
        assertIs<PendingRegistrationResult.Registered>(registry.registerPending(pending(duplicateId)))

        val signaling = CapturingClient()
        val contacts = TrustedContactManager(InMemoryTrustedContactStore(), CountingRandom(40))
        val responder = TrustedInboundSessionResponder(
            signalingClient = signaling,
            identities = identityManager(50),
            presence = presenceManager(60),
            helloVerifier = TrustedInboundHelloVerifier(contacts, handshake, signer),
            handshake = handshake,
            signer = signer,
            keyAgreement = agreement,
            keyDeriver = deriver,
        )
        val coordinator = ManagedTrustedSessionCoordinator(responder, registry)

        try {
            val result = coordinator.beginInbound(
                SharingContextId("unused-context"),
                duplicateId,
                RoutedHandshakeMessage(
                    message = DecodedPeerMessage.Hello(
                        dev.veilshare.core.model.SessionHello(
                            sharingIdentityIdHash = "unused",
                            sharingPublicKey = "unused",
                            sessionIdHash = "unused",
                            signature = "unused",
                        ),
                    ),
                    replyReferenceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
                ),
            )
            assertIs<ManagedInboundBeginResult.RegistryDuplicate>(result)
            assertTrue(signaling.relays.isEmpty())
        } finally {
            registry.shutdown()
        }
    }

    private fun pending(sessionId: SessionId): PendingInboundHandshake {
        val ephemeral = agreement.generateKeyPair()
        return PendingInboundHandshake(
            sessionId = sessionId,
            peer = dev.veilshare.core.contacts.PinnedPeerIdentity(
                contactId = ContactId("occupied-contact-${sessionId.value}"),
                sharingIdentityId = SharingIdentityId("occupied-peer-${sessionId.value}"),
                sharingIdentityIdHash = Hash.sha256("occupied-peer-${sessionId.value}".encodeToByteArray()).toHex(),
                publicKey = dev.veilshare.core.crypto.Ed25519PublicKey(ByteArray(32) { 1 }),
                fingerprint = Fingerprint("occupied-fingerprint-${sessionId.value}"),
            ),
            localIdentity = SharingPublicIdentity(
                contextId = SharingContextId("occupied-context-${sessionId.value}"),
                identityId = SharingIdentityId("occupied-local-${sessionId.value}"),
                publicKey = dev.veilshare.core.crypto.Ed25519PublicKey(ByteArray(32) { 2 }),
                fingerprint = Fingerprint("occupied-local-fingerprint-${sessionId.value}"),
            ),
            localReferenceCode = ReferenceCodes.parse("JKLM-NPQR-STUV-WXYZ"),
            peerReferenceCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH"),
            receiverEphemeral = ephemeral,
            handshake = handshake,
            signer = signer,
            keyAgreement = agreement,
            keyDeriver = deriver,
        )
    }

    private fun identityManager(seed: Int) = SharingIdentityManager(
        InMemorySharingIdentityStore(),
        CountingRandom(seed),
        signer,
    )

    private fun presenceManager(seed: Int) = SharingPresenceManager(
        InMemorySharingPresenceStore(),
        CountingRandom(seed),
    )

    private class CapturingClient : SignalingClient {
        override val incoming: Flow<SignalingEnvelope> = MutableSharedFlow()
        val relays = mutableListOf<RelayRequest>()
        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun unregister(request: UnregisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse = LookupResponse(LookupStatus.NOT_FOUND)
        override suspend fun relay(request: RelayRequest) {
            relays += request
        }
        override suspend fun close() = Unit
    }

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var counter = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 43 + index) and 0xff).toByte() }
        }
    }

    private class MutableClock : TransferClock {
        private var now = 0L
        override fun nowMillis(): Long = now
    }
}
