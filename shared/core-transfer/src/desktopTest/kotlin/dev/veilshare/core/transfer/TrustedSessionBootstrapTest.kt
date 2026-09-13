package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.ContactVerificationMethod
import dev.veilshare.core.contacts.InMemoryTrustedContactStore
import dev.veilshare.core.contacts.LookupTrustResolver
import dev.veilshare.core.contacts.PeerIdentityCandidate
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.crypto.toBase64
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.identity.InMemorySharingIdentityStore
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.model.LookupRequest
import dev.veilshare.core.model.LookupResponse
import dev.veilshare.core.model.LookupStatus
import dev.veilshare.core.model.MessageId
import dev.veilshare.core.model.MessageType
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.RegisterRequest
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
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

class TrustedSessionBootstrapTest {
    private val signer = JvmEd25519Signer()
    private val handshake = JvmHandshakeProtocol()
    private val peerCode = ReferenceCodes.parse("2345-6789-ABCD-EFGH")

    @Test
    fun `trusted lookup is the only path that sends SESSION_HELLO`() = runTest {
        val remoteKeys = signer.generateKeyPair()
        try {
            val remoteId = SharingIdentityId("remote-trusted")
            val contacts = contacts()
            contacts.addVerified(
                alias = "Remote",
                candidate = PeerIdentityCandidate(remoteId, remoteKeys.publicKey, peerCode),
                verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
            )

            val signaling = CapturingSignalingClient(
                LookupResponse(
                    status = LookupStatus.FOUND,
                    sharingIdentityId = remoteId,
                    sharingPublicKey = remoteKeys.publicKey.bytes.toBase64(),
                ),
            )
            val identities = identities()
            val starter = TrustedOutboundSessionStarter(
                signalingClient = signaling,
                identities = identities,
                trustResolver = LookupTrustResolver(contacts),
                handshake = handshake,
                signer = signer,
                random = CountingRandom(90),
            )

            val result = assertIs<OutboundSessionStartResult.Started>(
                starter.start(SharingContextId("real-context"), peerCode),
            )
            assertEquals(1, signaling.relays.size)
            assertContentEquals(remoteKeys.publicKey.bytes, result.peer.publicKey.bytes)

            val relay = signaling.relays.single()
            assertEquals(peerCode, relay.toReferenceCode)
            assertEquals(result.sessionId, relay.sessionId)

            val decoded = HandshakeSignalingInbox().decodeRelay(
                SignalingEnvelope(
                    protocolVersion = SharingProtocol.VERSION,
                    messageId = MessageId("forwarded-hello"),
                    type = MessageType.RELAY,
                    sessionId = result.sessionId,
                    payload = relay.opaquePayload,
                ),
            )
            val hello = assertIs<DecodedPeerMessage.Hello>(decoded).value
            assertEquals(result.hello, hello)

            handshake.verifySessionHello(
                hello = hello,
                expectedSharingIdentityIdHash = Hash.sha256(result.localIdentity.identityId.value.encodeToByteArray()).toHex(),
                expectedSessionIdHash = Hash.sha256(result.sessionId.value.encodeToByteArray()).toHex(),
                expectedSenderPublicKey = result.localIdentity.publicKey,
                signer = signer,
            )
        } finally {
            remoteKeys.privateKey.material.close()
        }
    }

    @Test
    fun `unknown peer requires verification and sends no relay`() = runTest {
        val remoteKeys = signer.generateKeyPair()
        try {
            val signaling = CapturingSignalingClient(
                LookupResponse(
                    status = LookupStatus.FOUND,
                    sharingIdentityId = SharingIdentityId("remote-new"),
                    sharingPublicKey = remoteKeys.publicKey.bytes.toBase64(),
                ),
            )
            val starter = starter(signaling, contacts())

            assertIs<OutboundSessionStartResult.NeedsVerification>(
                starter.start(SharingContextId("real-context"), peerCode),
            )
            assertTrue(signaling.relays.isEmpty())
        } finally {
            remoteKeys.privateKey.material.close()
        }
    }

    @Test
    fun `pinned identity with substituted key is blocked before relay`() = runTest {
        val pinnedKeys = signer.generateKeyPair()
        val attackerKeys = signer.generateKeyPair()
        try {
            val remoteId = SharingIdentityId("remote-pinned")
            val contacts = contacts()
            contacts.addVerified(
                alias = "Pinned",
                candidate = PeerIdentityCandidate(remoteId, pinnedKeys.publicKey, peerCode),
                verificationMethod = ContactVerificationMethod.QR_CODE,
            )
            val signaling = CapturingSignalingClient(
                LookupResponse(
                    status = LookupStatus.FOUND,
                    sharingIdentityId = remoteId,
                    sharingPublicKey = attackerKeys.publicKey.bytes.toBase64(),
                ),
            )
            val starter = starter(signaling, contacts)

            assertIs<OutboundSessionStartResult.KeyMismatch>(
                starter.start(SharingContextId("real-context"), peerCode),
            )
            assertTrue(signaling.relays.isEmpty())
        } finally {
            pinnedKeys.privateKey.material.close()
            attackerKeys.privateKey.material.close()
        }
    }

    @Test
    fun `lookup unavailable sends no relay`() = runTest {
        val signaling = CapturingSignalingClient(LookupResponse(status = LookupStatus.NOT_FOUND))
        val starter = starter(signaling, contacts())

        val result = assertIs<OutboundSessionStartResult.Unavailable>(
            starter.start(SharingContextId("real-context"), peerCode),
        )
        assertEquals(LookupStatus.NOT_FOUND, result.status)
        assertTrue(signaling.relays.isEmpty())
    }

    @Test
    fun `malformed lookup key fails closed before relay`() = runTest {
        val signaling = CapturingSignalingClient(
            LookupResponse(
                status = LookupStatus.FOUND,
                sharingIdentityId = SharingIdentityId("remote-malformed"),
                sharingPublicKey = "not-valid-base64!",
            ),
        )
        val starter = starter(signaling, contacts())

        assertFailsWith<IllegalArgumentException> {
            starter.start(SharingContextId("real-context"), peerCode)
        }
        assertTrue(signaling.relays.isEmpty())
    }

    private fun starter(
        signaling: CapturingSignalingClient,
        contacts: TrustedContactManager,
    ) = TrustedOutboundSessionStarter(
        signalingClient = signaling,
        identities = identities(),
        trustResolver = LookupTrustResolver(contacts),
        handshake = handshake,
        signer = signer,
        random = CountingRandom(50),
    )

    private fun identities() = SharingIdentityManager(
        store = InMemorySharingIdentityStore(),
        random = CountingRandom(10),
        signer = signer,
    )

    private fun contacts() = TrustedContactManager(
        store = InMemoryTrustedContactStore(),
        random = CountingRandom(30),
    )

    private class CapturingSignalingClient(
        private val lookupResponse: LookupResponse,
    ) : SignalingClient {
        private val events = MutableSharedFlow<SignalingEnvelope>()
        override val incoming: Flow<SignalingEnvelope> = events
        val relays = mutableListOf<RelayRequest>()

        override suspend fun connect() = Unit
        override suspend fun register(request: RegisterRequest) = Unit
        override suspend fun lookup(request: LookupRequest): LookupResponse = lookupResponse
        override suspend fun relay(request: RelayRequest) {
            relays += request
        }
        override suspend fun close() = Unit
    }

    private class CountingRandom(start: Int) : RandomBytesSource {
        private var counter = start
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 31 + index) and 0xff).toByte() }
        }
    }
}
