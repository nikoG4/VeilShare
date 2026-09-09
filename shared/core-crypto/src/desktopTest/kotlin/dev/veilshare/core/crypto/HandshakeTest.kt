package dev.veilshare.core.crypto

import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.SessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HandshakeTest {
    private val signer = JvmEd25519Signer()
    private val keyAgreement = JvmX25519KeyAgreement()
    private val keyDeriver = JvmHkdfSha256KeyDeriver()

    @Test fun sessionHelloRoundTrip() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")

        val hello = HandshakeProtocol.createSessionHello(sharingIdentityId, sharingKeyPair, sessionId, signer)

        assertEquals(HandshakeProtocol.sha256(sharingIdentityId.value), hello.sharingIdentityIdHash)
        assertEquals(HandshakeProtocol.sha256(sessionId.value), hello.sessionIdHash)
        assertEquals(dev.veilshare.core.model.SharingProtocol.VERSION, hello.protocolVersion)

        val verifiedPublicKey = HandshakeProtocol.verifySessionHello(
            hello,
            HandshakeProtocol.sha256(sharingIdentityId.value),
            HandshakeProtocol.sha256(sessionId.value),
            signer,
        )
        assertEquals(sharingKeyPair.publicKey.bytes.contentToString(), verifiedPublicKey.bytes.contentToString())
    }

    @Test fun sessionHelloInvalidSignatureFails() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")

        val hello = HandshakeProtocol.createSessionHello(sharingIdentityId, sharingKeyPair, sessionId, signer)

        val tamperedHello = hello.copy(signature = "invalid".padEnd(88, 'A'))

        assertFailsWith<IllegalArgumentException> {
            HandshakeProtocol.verifySessionHello(
                tamperedHello,
                HandshakeProtocol.sha256(sharingIdentityId.value),
                HandshakeProtocol.sha256(sessionId.value),
                signer,
            )
        }
    }

    @Test fun sessionConfirmRoundTrip() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val receiverEphemeralKeyPair = keyAgreement.generateKeyPair()

        val confirm = HandshakeProtocol.createSessionConfirm(
            sharingIdentityId, sharingKeyPair, sessionId, receiverEphemeralKeyPair, signer
        )

        assertEquals(HandshakeProtocol.sha256(sharingIdentityId.value), confirm.sharingIdentityIdHash)
        assertEquals(HandshakeProtocol.sha256(sessionId.value), confirm.sessionIdHash)
        assertEquals(receiverEphemeralKeyPair.publicKey.bytes.toBase64(), confirm.receiverEphemeralPublicKey)

        val (verifiedSharingPublicKey, verifiedReceiverEphemeralPublicKey) = HandshakeProtocol.verifySessionConfirm(
            confirm,
            HandshakeProtocol.sha256(sharingIdentityId.value),
            HandshakeProtocol.sha256(sessionId.value),
            signer,
        )
        assertEquals(sharingKeyPair.publicKey.bytes.contentToString(), verifiedSharingPublicKey.bytes.contentToString())
        assertEquals(receiverEphemeralKeyPair.publicKey.bytes.contentToString(), verifiedReceiverEphemeralPublicKey.bytes.contentToString())
    }

    @Test fun sessionConfirmAckRoundTrip() = runTest {
        val sessionId = SessionId("test-session")
        val senderEphemeralKeyPair = keyAgreement.generateKeyPair()
        val receiverEphemeralKeyPair = keyAgreement.generateKeyPair()

        val helloProtocolVersion = 1
        val helloSenderSharingIdentityIdHash = "hello-sender-hash"
        val helloSessionIdHash = "hello-session-hash"
        val confirmProtocolVersion = 1
        val confirmReceiverSharingIdentityIdHash = "confirm-receiver-hash"
        val confirmSessionIdHash = "confirm-session-hash"
        val confirmEphemeralPublicKeyHash = HandshakeProtocol.sha256(receiverEphemeralKeyPair.publicKey.bytes)

        val ack = HandshakeProtocol.createSessionConfirmAck(
            sessionId, senderEphemeralKeyPair,
            helloProtocolVersion, helloSenderSharingIdentityIdHash, helloSessionIdHash,
            confirmProtocolVersion, confirmReceiverSharingIdentityIdHash, confirmSessionIdHash, confirmEphemeralPublicKeyHash
        )

        assertEquals(HandshakeProtocol.sha256(sessionId.value), ack.sessionIdHash)
        assertEquals(senderEphemeralKeyPair.publicKey.bytes.toBase64(), ack.senderEphemeralPublicKey)

        val expectedSenderEphemeralHash = HandshakeProtocol.sha256(senderEphemeralKeyPair.publicKey.bytes)

        val verifiedSenderEphemeralPublicKey = HandshakeProtocol.verifySessionConfirmAck(
            ack,
            HandshakeProtocol.sha256(sessionId.value),
            expectedSenderEphemeralHash,
            helloProtocolVersion, helloSenderSharingIdentityIdHash, helloSessionIdHash,
            confirmProtocolVersion, confirmReceiverSharingIdentityIdHash, confirmSessionIdHash, confirmEphemeralPublicKeyHash,
        )
        assertEquals(senderEphemeralKeyPair.publicKey.bytes.contentToString(), verifiedSenderEphemeralPublicKey.bytes.contentToString())
    }

    @Test fun deriveHandshakeKeysCommutative() = runTest {
        val senderEphemeralKeyPair = keyAgreement.generateKeyPair()
        val receiverEphemeralKeyPair = keyAgreement.generateKeyPair()

        val senderKeys = HandshakeProtocol.deriveHandshakeKeys(
            senderEphemeralKeyPair.privateKey,
            receiverEphemeralKeyPair.publicKey,
            keyAgreement,
            keyDeriver,
        )

        val receiverKeys = HandshakeProtocol.deriveHandshakeKeys(
            receiverEphemeralKeyPair.privateKey,
            senderEphemeralKeyPair.publicKey,
            keyAgreement,
            keyDeriver,
        )

        // ECDH is commutative: both sides should derive the same shared secret
        // So sender's s2rKey should equal receiver's s2rKey (same salt "s2r-key-v1")
        assertEquals(senderKeys.senderToReceiverKey.contentToString(), receiverKeys.senderToReceiverKey.contentToString())
        assertEquals(senderKeys.receiverToSenderKey.contentToString(), receiverKeys.receiverToSenderKey.contentToString())
        assertEquals(senderKeys.transcriptHash, receiverKeys.transcriptHash)
        assertNotNull(senderKeys.senderToReceiverKey)
        assertNotNull(senderKeys.receiverToSenderKey)
        assertNotNull(senderKeys.transcriptHash)
    }

    @Test fun transcriptHashIsDeterministic() = runTest {
        val transcript = HandshakeTranscript(
            helloProtocolVersion = 1,
            helloSenderSharingIdentityIdHash = "hello-sender-hash",
            helloSessionIdHash = "hello-session-hash",
            confirmProtocolVersion = 1,
            confirmReceiverSharingIdentityIdHash = "confirm-receiver-hash",
            confirmSessionIdHash = "confirm-session-hash",
            confirmEphemeralPublicKeyHash = "confirm-ephemeral-hash",
            ackProtocolVersion = 1,
            ackSessionIdHash = "ack-session-hash",
            ackEphemeralPublicKeyHash = "ack-ephemeral-hash",
            ackTranscriptHash = "ack-transcript-hash",
        )

        val hash1 = transcript.sha256()
        val hash2 = transcript.sha256()

        assertEquals(hash1, hash2)
    }

    @Test fun ed25519SignVerify() = runTest {
        val keyPair = signer.generateKeyPair()
        val message = "test message".encodeToByteArray()
        val signature = signer.sign(keyPair.privateKey, message)
        
        assertTrue(signer.verify(keyPair.publicKey, message, signature))
        
        // Wrong message should fail verification
        val wrongMessage = "wrong message".encodeToByteArray()
        assertTrue(signer.verify(keyPair.publicKey, wrongMessage, signature).not())
    }

    @Test fun x25519KeyAgreementCommutative() = runTest {
        val keyPairA = keyAgreement.generateKeyPair()
        val keyPairB = keyAgreement.generateKeyPair()

        val sharedA = keyAgreement.deriveSharedSecret(keyPairA.privateKey, keyPairB.publicKey)
        val sharedB = keyAgreement.deriveSharedSecret(keyPairB.privateKey, keyPairA.publicKey)

        assertEquals(sharedA.copy().contentToString(), sharedB.copy().contentToString())
    }
}