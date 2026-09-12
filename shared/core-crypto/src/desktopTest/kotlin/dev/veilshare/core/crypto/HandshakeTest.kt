package dev.veilshare.core.crypto

import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HandshakeTest {
    private val signer = JvmEd25519Signer()
    private val keyAgreement = JvmX25519KeyAgreement()
    private val keyDeriver = JvmHkdfSha256KeyDeriver()
    private val handshakeProtocol = JvmHandshakeProtocol()

    @Test
    fun sessionHelloRoundTrip() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")

        val hello = handshakeProtocol.createSessionHello(sharingIdentityId, sharingKeyPair, sessionId, signer)

        assertEquals(identityHash(sharingIdentityId), hello.sharingIdentityIdHash)
        assertEquals(sessionHash(sessionId), hello.sessionIdHash)
        handshakeProtocol.verifySessionHello(
            hello = hello,
            expectedSharingIdentityIdHash = identityHash(sharingIdentityId),
            expectedSessionIdHash = sessionHash(sessionId),
            expectedSenderPublicKey = sharingKeyPair.publicKey,
            signer = signer,
        )
    }

    @Test
    fun sessionHelloRejectsSignatureAndPeerKeySubstitution() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val attacker = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val hello = handshakeProtocol.createSessionHello(sharingIdentityId, sharingKeyPair, sessionId, signer)

        assertFailsWith<IllegalArgumentException> {
            handshakeProtocol.verifySessionHello(
                hello.copy(signature = "invalid".padEnd(88, 'A')),
                identityHash(sharingIdentityId),
                sessionHash(sessionId),
                sharingKeyPair.publicKey,
                signer,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            handshakeProtocol.verifySessionHello(
                hello,
                identityHash(sharingIdentityId),
                sessionHash(sessionId),
                attacker.publicKey,
                signer,
            )
        }
    }

    @Test
    fun sessionConfirmRoundTripAndEphemeralMutationFails() = runTest {
        val receiverIdentityId = SharingIdentityId("receiver-identity")
        val receiverIdentityKeys = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val receiverEphemeral = keyAgreement.generateKeyPair()

        val confirm = handshakeProtocol.createSessionConfirm(
            receiverIdentityId,
            receiverIdentityKeys,
            sessionId,
            receiverEphemeral,
            signer,
        )

        handshakeProtocol.verifySessionConfirm(
            confirm = confirm,
            expectedSharingIdentityIdHash = identityHash(receiverIdentityId),
            expectedSessionIdHash = sessionHash(sessionId),
            expectedReceiverEphemeralPublicKey = receiverEphemeral.publicKey,
            expectedReceiverIdentityPublicKey = receiverIdentityKeys.publicKey,
            signer = signer,
        )

        val attackerEphemeral = keyAgreement.generateKeyPair()
        assertFailsWith<IllegalArgumentException> {
            handshakeProtocol.verifySessionConfirm(
                confirm.copy(receiverEphemeralPublicKey = attackerEphemeral.publicKey.bytes.toBase64()),
                identityHash(receiverIdentityId),
                sessionHash(sessionId),
                receiverEphemeral.publicKey,
                receiverIdentityKeys.publicKey,
                signer,
            )
        }
    }

    @Test
    fun sessionConfirmAckIsSignedByExpectedSenderIdentity() = runTest {
        val senderIdentityId = SharingIdentityId("sender-identity")
        val receiverIdentityId = SharingIdentityId("receiver-identity")
        val senderIdentityKeys = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val senderEphemeral = keyAgreement.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()

        val ack = handshakeProtocol.createSessionConfirmAck(
            senderSharingIdentityId = senderIdentityId,
            senderSharingKeyPair = senderIdentityKeys,
            receiverSharingIdentityIdHash = identityHash(receiverIdentityId),
            sessionId = sessionId,
            senderEphemeralKeyPair = senderEphemeral,
            receiverEphemeralPublicKey = receiverEphemeral.publicKey,
            signer = signer,
        )

        val verified = handshakeProtocol.verifySessionConfirmAck(
            ack = ack,
            expectedSenderSharingIdentityIdHash = identityHash(senderIdentityId),
            expectedReceiverSharingIdentityIdHash = identityHash(receiverIdentityId),
            expectedSessionIdHash = sessionHash(sessionId),
            expectedReceiverEphemeralPublicKey = receiverEphemeral.publicKey,
            expectedSenderIdentityPublicKey = senderIdentityKeys.publicKey,
            signer = signer,
        )

        assertContentEquals(senderEphemeral.publicKey.bytes, verified.senderEphemeralPublicKey.bytes)
        assertEquals(ack.transcriptHash, verified.transcript.computeTranscriptHash())
    }

    @Test
    fun forgedAckWithReplacedSenderEphemeralFailsEvenWithRecomputedTranscriptHash() = runTest {
        val senderIdentityId = SharingIdentityId("sender-identity")
        val receiverIdentityId = SharingIdentityId("receiver-identity")
        val senderIdentityKeys = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val senderEphemeral = keyAgreement.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        val attackerEphemeral = keyAgreement.generateKeyPair()

        val ack = handshakeProtocol.createSessionConfirmAck(
            senderIdentityId,
            senderIdentityKeys,
            identityHash(receiverIdentityId),
            sessionId,
            senderEphemeral,
            receiverEphemeral.publicKey,
            signer,
        )

        val forgedSenderEphemeral = attackerEphemeral.publicKey.bytes.toBase64()
        val forgedTranscript = HandshakeTranscript(
            protocolVersion = ack.protocolVersion,
            senderIdentityIdHash = identityHash(senderIdentityId),
            receiverIdentityIdHash = identityHash(receiverIdentityId),
            sessionIdHash = sessionHash(sessionId),
            senderEphemeralPublicKey = forgedSenderEphemeral,
            receiverEphemeralPublicKey = receiverEphemeral.publicKey.bytes.toBase64(),
        )
        val forgedAck = ack.copy(
            senderEphemeralPublicKey = forgedSenderEphemeral,
            transcriptHash = forgedTranscript.computeTranscriptHash(),
            // Attacker cannot replace this with a valid signature from sender identity.
            signature = ack.signature,
        )

        assertFailsWith<IllegalArgumentException> {
            handshakeProtocol.verifySessionConfirmAck(
                forgedAck,
                identityHash(senderIdentityId),
                identityHash(receiverIdentityId),
                sessionHash(sessionId),
                receiverEphemeral.publicKey,
                senderIdentityKeys.publicKey,
                signer,
            )
        }
    }

    @Test
    fun ackFailsAgainstWrongTrustedSenderIdentityKey() = runTest {
        val senderIdentityId = SharingIdentityId("sender-identity")
        val receiverIdentityId = SharingIdentityId("receiver-identity")
        val senderIdentityKeys = signer.generateKeyPair()
        val wrongIdentityKeys = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val senderEphemeral = keyAgreement.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()

        val ack = handshakeProtocol.createSessionConfirmAck(
            senderIdentityId,
            senderIdentityKeys,
            identityHash(receiverIdentityId),
            sessionId,
            senderEphemeral,
            receiverEphemeral.publicKey,
            signer,
        )

        assertFailsWith<IllegalArgumentException> {
            handshakeProtocol.verifySessionConfirmAck(
                ack,
                identityHash(senderIdentityId),
                identityHash(receiverIdentityId),
                sessionHash(sessionId),
                receiverEphemeral.publicKey,
                wrongIdentityKeys.publicKey,
                signer,
            )
        }
    }

    @Test
    fun bothPeersDeriveSameTranscriptBoundDirectionalKeys() = runTest {
        val senderEphemeral = keyAgreement.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        val transcript = transcript(senderEphemeral, receiverEphemeral, "session-a")

        val senderKeys = handshakeProtocol.deriveHandshakeKeys(
            senderEphemeral.privateKey,
            receiverEphemeral.publicKey,
            transcript,
            keyAgreement,
            keyDeriver,
        )
        val receiverKeys = handshakeProtocol.deriveHandshakeKeys(
            receiverEphemeral.privateKey,
            senderEphemeral.publicKey,
            transcript,
            keyAgreement,
            keyDeriver,
        )

        assertContentEquals(senderKeys.senderToReceiverKey, receiverKeys.senderToReceiverKey)
        assertContentEquals(senderKeys.receiverToSenderKey, receiverKeys.receiverToSenderKey)
        assertEquals(senderKeys.transcriptHash, receiverKeys.transcriptHash)
        assertFalse(senderKeys.senderToReceiverKey.contentEquals(senderKeys.receiverToSenderKey))
    }

    @Test
    fun sameX25519SecretProducesDifferentKeysForDifferentTranscript() = runTest {
        val senderEphemeral = keyAgreement.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        val transcriptA = transcript(senderEphemeral, receiverEphemeral, "session-a")
        val transcriptB = transcript(senderEphemeral, receiverEphemeral, "session-b")

        val keysA = handshakeProtocol.deriveHandshakeKeys(
            senderEphemeral.privateKey,
            receiverEphemeral.publicKey,
            transcriptA,
            keyAgreement,
            keyDeriver,
        )
        val keysB = handshakeProtocol.deriveHandshakeKeys(
            senderEphemeral.privateKey,
            receiverEphemeral.publicKey,
            transcriptB,
            keyAgreement,
            keyDeriver,
        )

        assertFalse(keysA.senderToReceiverKey.contentEquals(keysB.senderToReceiverKey))
        assertFalse(keysA.receiverToSenderKey.contentEquals(keysB.receiverToSenderKey))
        assertNotEquals(keysA.transcriptHash, keysB.transcriptHash)
    }

    @Test
    fun transcriptHashChangesWhenAnyBoundFieldChanges() {
        val senderEphemeral = keyAgreement.generateKeyPair()
        val receiverEphemeral = keyAgreement.generateKeyPair()
        val base = transcript(senderEphemeral, receiverEphemeral, "session-a")
        val baseHash = base.computeTranscriptHash()

        assertNotEquals(baseHash, base.copy(senderIdentityIdHash = "different-sender").computeTranscriptHash())
        assertNotEquals(baseHash, base.copy(receiverIdentityIdHash = "different-receiver").computeTranscriptHash())
        assertNotEquals(baseHash, base.copy(sessionIdHash = "different-session").computeTranscriptHash())
        assertNotEquals(baseHash, base.copy(senderEphemeralPublicKey = "different-ephemeral").computeTranscriptHash())
        assertNotEquals(baseHash, base.copy(receiverEphemeralPublicKey = "different-ephemeral").computeTranscriptHash())
    }

    @Test
    fun canonicalEncodingIsNotAmbiguousAcrossFieldBoundaries() {
        val one = HandshakeCanonical.encode(
            domain = "domain",
            protocolVersion = 1,
            fields = listOf("a|b", "c"),
        )
        val two = HandshakeCanonical.encode(
            domain = "domain",
            protocolVersion = 1,
            fields = listOf("a", "b|c"),
        )
        assertFalse(one.contentEquals(two))
    }

    @Test
    fun ed25519SignVerify() {
        val keyPair = signer.generateKeyPair()
        val message = "test message".encodeToByteArray()
        val signature = signer.sign(keyPair.privateKey, message)
        assertTrue(signer.verify(keyPair.publicKey, message, signature))
        assertFalse(signer.verify(keyPair.publicKey, "wrong message".encodeToByteArray(), signature))
    }

    @Test
    fun x25519KeyAgreementCommutative() {
        val keyPairA = keyAgreement.generateKeyPair()
        val keyPairB = keyAgreement.generateKeyPair()
        val sharedA = keyAgreement.deriveSharedSecret(keyPairA.privateKey, keyPairB.publicKey)
        val sharedB = keyAgreement.deriveSharedSecret(keyPairB.privateKey, keyPairA.publicKey)
        try {
            assertContentEquals(sharedA.copy(), sharedB.copy())
        } finally {
            sharedA.close()
            sharedB.close()
        }
    }

    private fun transcript(
        senderEphemeral: X25519KeyPair,
        receiverEphemeral: X25519KeyPair,
        session: String,
    ) = HandshakeTranscript(
        protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
        senderIdentityIdHash = "sender-identity-hash",
        receiverIdentityIdHash = "receiver-identity-hash",
        sessionIdHash = Hash.sha256(session.encodeToByteArray()).toHex(),
        senderEphemeralPublicKey = senderEphemeral.publicKey.bytes.toBase64(),
        receiverEphemeralPublicKey = receiverEphemeral.publicKey.bytes.toBase64(),
    )

    private fun identityHash(id: SharingIdentityId): String =
        Hash.sha256(id.value.encodeToByteArray()).toHex()

    private fun sessionHash(id: SessionId): String =
        Hash.sha256(id.value.encodeToByteArray()).toHex()
}
