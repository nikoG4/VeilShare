package dev.veilshare.core.crypto

import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.FileId
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
    private val handshakeProtocol = JvmHandshakeProtocol()

    @Test fun sessionHelloRoundTrip() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")

        val hello = handshakeProtocol.createSessionHello(sharingIdentityId, sharingKeyPair, sessionId, signer)

        assertEquals(Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex(), hello.sharingIdentityIdHash)
        assertEquals(Hash.sha256(sessionId.value.encodeToByteArray()).toHex(), hello.sessionIdHash)
        assertEquals(dev.veilshare.core.model.SharingProtocol.VERSION, hello.protocolVersion)

        handshakeProtocol.verifySessionHello(
            hello,
            Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex(),
            Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
            sharingKeyPair.publicKey,
            signer,
        )
    }

    @Test fun sessionHelloInvalidSignatureFails() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")

        val hello = handshakeProtocol.createSessionHello(sharingIdentityId, sharingKeyPair, sessionId, signer)

        val tamperedHello = hello.copy(signature = "invalid".padEnd(88, 'A'))

        assertFailsWith<IllegalArgumentException> {
            handshakeProtocol.verifySessionHello(
                tamperedHello,
                Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex(),
                Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
                sharingKeyPair.publicKey,
                signer,
            )
        }
    }

    @Test fun sessionConfirmRoundTrip() = runTest {
        val sharingIdentityId = SharingIdentityId("test-identity")
        val sharingKeyPair = signer.generateKeyPair()
        val sessionId = SessionId("test-session")
        val receiverEphemeralKeyPair = keyAgreement.generateKeyPair()

        val confirm = handshakeProtocol.createSessionConfirm(
            sharingIdentityId, sharingKeyPair, sessionId, receiverEphemeralKeyPair, signer
        )

        assertEquals(Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex(), confirm.sharingIdentityIdHash)
        assertEquals(Hash.sha256(sessionId.value.encodeToByteArray()).toHex(), confirm.sessionIdHash)
        assertEquals(receiverEphemeralKeyPair.publicKey.bytes.toBase64(), confirm.receiverEphemeralPublicKey)

        handshakeProtocol.verifySessionConfirm(
            confirm,
            Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex(),
            Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
            receiverEphemeralKeyPair.publicKey,
            sharingKeyPair.publicKey,
            signer,
        )
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
        val confirmEphemeralPublicKeyHash = Hash.sha256(receiverEphemeralKeyPair.publicKey.bytes).toHex()

        val transcript = HandshakeTranscript(
            protocolVersion = helloProtocolVersion,
            senderIdentityIdHash = helloSenderSharingIdentityIdHash,
            receiverIdentityIdHash = confirmReceiverSharingIdentityIdHash,
            sessionIdHash = helloSessionIdHash,
            senderEphemeralPublicKey = confirmEphemeralPublicKeyHash,
            receiverEphemeralPublicKey = confirmEphemeralPublicKeyHash,
        )

        val ack = handshakeProtocol.createSessionConfirmAck(
            sessionId, senderEphemeralKeyPair, transcript
        )

        assertEquals(Hash.sha256(sessionId.value.encodeToByteArray()).toHex(), ack.sessionIdHash)
        assertEquals(senderEphemeralKeyPair.publicKey.bytes.toBase64(), ack.senderEphemeralPublicKey)

        val expectedSenderEphemeralHash = Hash.sha256(senderEphemeralKeyPair.publicKey.bytes).toHex()

        val verifiedSenderEphemeralPublicKey = handshakeProtocol.verifySessionConfirmAck(
            ack,
            Hash.sha256(sessionId.value.encodeToByteArray()).toHex(),
            expectedSenderEphemeralHash,
            transcript,
        )
        assertEquals(senderEphemeralKeyPair.publicKey.bytes.contentToString(), verifiedSenderEphemeralPublicKey.bytes.contentToString())
    }

    @Test fun deriveHandshakeKeysCommutative() = runTest {
        val senderEphemeralKeyPair = keyAgreement.generateKeyPair()
        val receiverEphemeralKeyPair = keyAgreement.generateKeyPair()

        val senderKeys = handshakeProtocol.deriveHandshakeKeys(
            senderEphemeralKeyPair.privateKey,
            receiverEphemeralKeyPair.publicKey,
            keyAgreement,
            keyDeriver,
        )

        val receiverKeys = handshakeProtocol.deriveHandshakeKeys(
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
            protocolVersion = 1,
            senderIdentityIdHash = "hello-sender-hash",
            receiverIdentityIdHash = "confirm-receiver-hash",
            sessionIdHash = "hello-session-hash",
            senderEphemeralPublicKey = "confirm-ephemeral-hash",
            receiverEphemeralPublicKey = "ack-ephemeral-hash",
        )

        val hash1 = transcript.computeTranscriptHash()
        val hash2 = transcript.computeTranscriptHash()

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