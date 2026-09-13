package dev.veilshare.core.transfer

import dev.veilshare.core.contacts.ContactVerificationMethod
import dev.veilshare.core.contacts.InMemoryTrustedContactStore
import dev.veilshare.core.contacts.PeerIdentityCandidate
import dev.veilshare.core.contacts.TrustedContactManager
import dev.veilshare.core.crypto.JvmEd25519Signer
import dev.veilshare.core.crypto.JvmHandshakeProtocol
import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TrustedInboundHandshakeTest {
    private val signer = JvmEd25519Signer()
    private val handshake = JvmHandshakeProtocol()

    @Test
    fun `known inbound identity verifies only with pinned key`() = runTest {
        val senderKeys = signer.generateKeyPair()
        try {
            val senderId = SharingIdentityId("known-sender")
            val contacts = contacts()
            val contact = contacts.addVerified(
                alias = "Known Sender",
                candidate = PeerIdentityCandidate(senderId, senderKeys.publicKey),
                verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
            )
            val sessionId = SessionId("inbound-session")
            val hello = handshake.createSessionHello(senderId, senderKeys, sessionId, signer)

            val result = assertIs<InboundHelloTrustResult.Trusted>(
                TrustedInboundHelloVerifier(contacts, handshake, signer).verify(sessionId, hello),
            )

            assertContentEquals(senderKeys.publicKey.bytes, result.peer.publicKey.bytes)
            kotlin.test.assertEquals(contact.contactId, result.peer.contactId)
        } finally {
            senderKeys.privateKey.material.close()
        }
    }

    @Test
    fun `unknown inbound identity is blocked for out of band verification`() = runTest {
        val unknownKeys = signer.generateKeyPair()
        try {
            val sessionId = SessionId("unknown-session")
            val hello = handshake.createSessionHello(
                SharingIdentityId("unknown-sender"),
                unknownKeys,
                sessionId,
                signer,
            )

            val result = assertIs<InboundHelloTrustResult.UnknownIdentity>(
                TrustedInboundHelloVerifier(contacts(), handshake, signer).verify(sessionId, hello),
            )
            assertNotEquals("", result.sharingIdentityIdHash)
            assertNotEquals("", result.presentedFingerprint.value)
        } finally {
            unknownKeys.privateKey.material.close()
        }
    }

    @Test
    fun `same identity signed by substituted key fails against pin`() = runTest {
        val pinnedKeys = signer.generateKeyPair()
        val attackerKeys = signer.generateKeyPair()
        try {
            val senderId = SharingIdentityId("pinned-sender")
            val contacts = contacts()
            contacts.addVerified(
                alias = "Pinned Sender",
                candidate = PeerIdentityCandidate(senderId, pinnedKeys.publicKey),
                verificationMethod = ContactVerificationMethod.QR_CODE,
            )
            val sessionId = SessionId("substitution-session")
            val forgedHello = handshake.createSessionHello(senderId, attackerKeys, sessionId, signer)

            assertFailsWith<IllegalArgumentException> {
                TrustedInboundHelloVerifier(contacts, handshake, signer).verify(sessionId, forgedHello)
            }
        } finally {
            pinnedKeys.privateKey.material.close()
            attackerKeys.privateKey.material.close()
        }
    }

    @Test
    fun `tampered hello signature fails for pinned peer`() = runTest {
        val senderKeys = signer.generateKeyPair()
        try {
            val senderId = SharingIdentityId("signed-sender")
            val contacts = contacts()
            contacts.addVerified(
                alias = "Signed Sender",
                candidate = PeerIdentityCandidate(senderId, senderKeys.publicKey),
                verificationMethod = ContactVerificationMethod.MANUAL_FINGERPRINT,
            )
            val sessionId = SessionId("signed-session")
            val hello = handshake.createSessionHello(senderId, senderKeys, sessionId, signer)
            val tampered = hello.copy(signature = hello.signature.reversed())

            assertFailsWith<IllegalArgumentException> {
                TrustedInboundHelloVerifier(contacts, handshake, signer).verify(sessionId, tampered)
            }
        } finally {
            senderKeys.privateKey.material.close()
        }
    }

    @Test
    fun `hello replayed into another session fails`() = runTest {
        val senderKeys = signer.generateKeyPair()
        try {
            val senderId = SharingIdentityId("session-bound-sender")
            val contacts = contacts()
            contacts.addVerified(
                alias = "Session Bound",
                candidate = PeerIdentityCandidate(senderId, senderKeys.publicKey),
                verificationMethod = ContactVerificationMethod.QR_CODE,
            )
            val originalSession = SessionId("session-a")
            val hello = handshake.createSessionHello(senderId, senderKeys, originalSession, signer)

            assertFailsWith<IllegalArgumentException> {
                TrustedInboundHelloVerifier(contacts, handshake, signer).verify(
                    SessionId("session-b"),
                    hello,
                )
            }
        } finally {
            senderKeys.privateKey.material.close()
        }
    }

    private fun contacts() = TrustedContactManager(
        store = InMemoryTrustedContactStore(),
        random = CountingRandom(),
    )

    private class CountingRandom : RandomBytesSource {
        private var counter = 1
        override fun nextBytes(size: Int): ByteArray {
            val marker = counter++
            return ByteArray(size) { index -> ((marker * 17 + index) and 0xff).toByte() }
        }
    }
}
