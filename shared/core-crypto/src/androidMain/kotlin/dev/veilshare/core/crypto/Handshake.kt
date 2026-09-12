package dev.veilshare.core.crypto

import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64

actual object Hash {
    actual fun sha256(input: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }
}

actual fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

actual fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

actual fun String.decodeFromBase64(): ByteArray = Base64.getDecoder().decode(this)

class AndroidHandshakeProtocol : HandshakeProtocol {

    override fun createSessionHello(
        sharingIdentityId: SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: SessionId,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionHello {
        val sharingIdentityIdHash = Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex()
        val sessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex()
        val message = "SESSION_HELLO|$sharingIdentityIdHash|$sessionIdHash|${dev.veilshare.core.model.SharingProtocol.VERSION}".encodeToByteArray()
        val signature = signer.sign(sharingKeyPair.privateKey, message)
        return dev.veilshare.core.model.SessionHello(
            sharingIdentityIdHash = sharingIdentityIdHash,
            sharingPublicKey = sharingKeyPair.publicKey.bytes.toBase64(),
            sessionIdHash = sessionIdHash,
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            signature = signature.toBase64(),
        )
    }

    override fun verifySessionHello(
        hello: dev.veilshare.core.model.SessionHello,
        expectedSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        expectedSenderPublicKey: Ed25519PublicKey,
        signer: Ed25519Signer,
    ) {
        if (hello.protocolVersion != dev.veilshare.core.model.SharingProtocol.VERSION) {
            throw IllegalArgumentException("Protocol version mismatch")
        }
        if (hello.sharingIdentityIdHash != expectedSharingIdentityIdHash) {
            throw IllegalArgumentException("Sharing identity ID hash mismatch")
        }
        if (hello.sessionIdHash != expectedSessionIdHash) {
            throw IllegalArgumentException("Session ID hash mismatch")
        }
        val message = "SESSION_HELLO|${hello.sharingIdentityIdHash}|${hello.sessionIdHash}|${hello.protocolVersion}".encodeToByteArray()
        val signature = hello.signature.decodeFromBase64()
        if (!signer.verify(expectedSenderPublicKey, message, signature)) {
            throw IllegalArgumentException("Invalid signature on SESSION_HELLO")
        }
        val actualPublicKey = Ed25519PublicKey(hello.sharingPublicKey.decodeFromBase64())
        if (!actualPublicKey.bytes.contentEquals(expectedSenderPublicKey.bytes)) {
            throw IllegalArgumentException("Sender public key does not match expected peer identity")
        }
    }

    override fun createSessionConfirm(
        sharingIdentityId: SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: SessionId,
        receiverEphemeralKeyPair: X25519KeyPair,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionConfirm {
        val sharingIdentityIdHash = Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex()
        val sessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex()
        val message = "SESSION_CONFIRM|$sharingIdentityIdHash|$sessionIdHash|${receiverEphemeralKeyPair.publicKey.bytes.toBase64()}|${dev.veilshare.core.model.SharingProtocol.VERSION}".encodeToByteArray()
        val signature = signer.sign(sharingKeyPair.privateKey, message)
        return dev.veilshare.core.model.SessionConfirm(
            sharingIdentityIdHash = sharingIdentityIdHash,
            sharingPublicKey = sharingKeyPair.publicKey.bytes.toBase64(),
            sessionIdHash = sessionIdHash,
            receiverEphemeralPublicKey = receiverEphemeralKeyPair.publicKey.bytes.toBase64(),
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            signature = signature.toBase64(),
        )
    }

    override fun verifySessionConfirm(
        confirm: dev.veilshare.core.model.SessionConfirm,
        expectedSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        expectedReceiverEphemeralPublicKey: X25519PublicKey,
        expectedReceiverIdentityPublicKey: Ed25519PublicKey,
        signer: Ed25519Signer,
    ) {
        if (confirm.protocolVersion != dev.veilshare.core.model.SharingProtocol.VERSION) {
            throw IllegalArgumentException("Protocol version mismatch")
        }
        if (confirm.sharingIdentityIdHash != expectedSharingIdentityIdHash) {
            throw IllegalArgumentException("Sharing identity ID hash mismatch")
        }
        if (confirm.sessionIdHash != expectedSessionIdHash) {
            throw IllegalArgumentException("Session ID hash mismatch")
        }
        val message = "SESSION_CONFIRM|${confirm.sharingIdentityIdHash}|${confirm.sessionIdHash}|${confirm.receiverEphemeralPublicKey}|${confirm.protocolVersion}".encodeToByteArray()
        val signature = confirm.signature.decodeFromBase64()
        if (!signer.verify(expectedReceiverIdentityPublicKey, message, signature)) {
            throw IllegalArgumentException("Invalid signature on SESSION_CONFIRM")
        }
        val actualEphemeralPublicKey = X25519PublicKey(confirm.receiverEphemeralPublicKey.decodeFromBase64())
        if (!actualEphemeralPublicKey.bytes.contentEquals(expectedReceiverEphemeralPublicKey.bytes)) {
            throw IllegalArgumentException("Receiver ephemeral public key does not match expected")
        }
        val actualIdentityPublicKey = Ed25519PublicKey(confirm.sharingPublicKey.decodeFromBase64())
        if (!actualIdentityPublicKey.bytes.contentEquals(expectedReceiverIdentityPublicKey.bytes)) {
            throw IllegalArgumentException("Receiver identity public key does not match expected peer identity")
        }
    }

    override fun createSessionConfirmAck(
        sessionId: SessionId,
        senderEphemeralKeyPair: X25519KeyPair,
        transcript: HandshakeTranscript,
    ): dev.veilshare.core.model.SessionConfirmAck {
        val sessionIdHash = Hash.sha256(sessionId.value.encodeToByteArray()).toHex()
        val senderEphemeralPublicKeyHash = Hash.sha256(senderEphemeralKeyPair.publicKey.bytes).toHex()
        val transcriptHash = transcript.computeTranscriptHash()
        return dev.veilshare.core.model.SessionConfirmAck(
            sessionIdHash = sessionIdHash,
            senderEphemeralPublicKey = senderEphemeralKeyPair.publicKey.bytes.toBase64(),
            transcriptHash = transcriptHash,
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
        )
    }

    override fun verifySessionConfirmAck(
        ack: dev.veilshare.core.model.SessionConfirmAck,
        expectedSessionIdHash: String,
        expectedSenderEphemeralPublicKeyHash: String,
        transcript: HandshakeTranscript,
    ): X25519PublicKey {
        if (ack.protocolVersion != dev.veilshare.core.model.SharingProtocol.VERSION) {
            throw IllegalArgumentException("Protocol version mismatch")
        }
        if (ack.sessionIdHash != expectedSessionIdHash) {
            throw IllegalArgumentException("Session ID hash mismatch")
        }
        val senderEphemeralPublicKey = X25519PublicKey(ack.senderEphemeralPublicKey.decodeFromBase64())
        if (Hash.sha256(senderEphemeralPublicKey.bytes).toHex() != expectedSenderEphemeralPublicKeyHash) {
            throw IllegalArgumentException("Sender ephemeral public key hash mismatch")
        }
        val expectedTranscriptHash = transcript.computeTranscriptHash()
        if (ack.transcriptHash != expectedTranscriptHash) {
            throw IllegalArgumentException("Transcript hash mismatch")
        }
        return senderEphemeralPublicKey
    }

    override suspend fun deriveHandshakeKeys(
        senderEphemeralPrivateKey: X25519PrivateKey,
        receiverEphemeralPublicKey: X25519PublicKey,
        keyAgreement: X25519KeyAgreement,
        keyDeriver: KeyDeriver,
    ): HandshakeKeys = runBlocking {
        val sharedSecret = keyAgreement.deriveSharedSecret(senderEphemeralPrivateKey, receiverEphemeralPublicKey)
        val transcriptKey = deriveHkdf(keyDeriver, sharedSecret, "transcript-binding-v1".encodeToByteArray(), 32)
        val s2rKey = deriveHkdf(keyDeriver, sharedSecret, "s2r-key-v1".encodeToByteArray(), 32)
        val r2sKey = deriveHkdf(keyDeriver, sharedSecret, "r2s-key-v1".encodeToByteArray(), 32)
        HandshakeKeys(
            senderToReceiverKey = s2rKey.copy(),
            receiverToSenderKey = r2sKey.copy(),
            transcriptHash = transcriptKey.copy().toHex(),
        )
    }

    private suspend fun deriveHkdf(keyDeriver: KeyDeriver, ikm: SensitiveBytes, salt: ByteArray, outputBytes: Int): SensitiveBytes {
        return keyDeriver.derive(ikm, salt, outputBytes)
    }
}