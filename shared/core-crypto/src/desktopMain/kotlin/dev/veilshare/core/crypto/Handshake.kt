package dev.veilshare.core.crypto

import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64

data class HandshakeTranscript(
    val helloProtocolVersion: Int,
    val helloSenderSharingIdentityIdHash: String,
    val helloSessionIdHash: String,
    val confirmProtocolVersion: Int,
    val confirmReceiverSharingIdentityIdHash: String,
    val confirmSessionIdHash: String,
    val confirmEphemeralPublicKeyHash: String,
    val ackProtocolVersion: Int,
    val ackSessionIdHash: String,
    val ackEphemeralPublicKeyHash: String,
    val ackTranscriptHash: String,
) {
    fun toByteArray(): ByteArray {
        val builder = StringBuilder()
        builder.append(helloProtocolVersion)
        builder.append(helloSenderSharingIdentityIdHash)
        builder.append(helloSessionIdHash)
        builder.append(confirmProtocolVersion)
        builder.append(confirmReceiverSharingIdentityIdHash)
        builder.append(confirmSessionIdHash)
        builder.append(confirmEphemeralPublicKeyHash)
        builder.append(ackProtocolVersion)
        builder.append(ackSessionIdHash)
        builder.append(ackEphemeralPublicKeyHash)
        builder.append(ackTranscriptHash)
        return builder.toString().encodeToByteArray()
    }

    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(toByteArray()).toHex()
    }

    fun toByteArrayWithoutAckTranscriptHash(): ByteArray {
        val builder = StringBuilder()
        builder.append(helloProtocolVersion)
        builder.append(helloSenderSharingIdentityIdHash)
        builder.append(helloSessionIdHash)
        builder.append(confirmProtocolVersion)
        builder.append(confirmReceiverSharingIdentityIdHash)
        builder.append(confirmSessionIdHash)
        builder.append(confirmEphemeralPublicKeyHash)
        builder.append(ackProtocolVersion)
        builder.append(ackSessionIdHash)
        builder.append(ackEphemeralPublicKeyHash)
        return builder.toString().encodeToByteArray()
    }

    fun sha256WithoutAckTranscriptHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(toByteArrayWithoutAckTranscriptHash()).toHex()
    }
}

data class HandshakeKeys(
    val senderToReceiverKey: ByteArray,
    val receiverToSenderKey: ByteArray,
    val transcriptHash: String,
)

object HandshakeProtocol {
    private const val TRANSCRIPT_BINDING_SALT = "transcript-binding-v1"
    private const val S2R_KEY_SALT = "s2r-key-v1"
    private const val R2S_KEY_SALT = "r2s-key-v1"

    fun sha256(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input).toHex()
    }

    fun sha256(input: String): String = sha256(input.encodeToByteArray())

    fun createSessionHello(
        sharingIdentityId: dev.veilshare.core.model.SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: dev.veilshare.core.model.SessionId,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionHello {
        val sharingIdentityIdHash = sha256(sharingIdentityId.value)
        val sessionIdHash = sha256(sessionId.value)
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

    fun verifySessionHello(
        hello: dev.veilshare.core.model.SessionHello,
        expectedSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        signer: Ed25519Signer,
    ): Ed25519PublicKey {
        if (hello.protocolVersion != dev.veilshare.core.model.SharingProtocol.VERSION) {
            throw IllegalArgumentException("Protocol version mismatch")
        }
        if (hello.sharingIdentityIdHash != expectedSharingIdentityIdHash) {
            throw IllegalArgumentException("Sharing identity ID hash mismatch")
        }
        if (hello.sessionIdHash != expectedSessionIdHash) {
            throw IllegalArgumentException("Session ID hash mismatch")
        }
        val publicKey = Ed25519PublicKey(hello.sharingPublicKey.decodeFromBase64())
        val message = "SESSION_HELLO|${hello.sharingIdentityIdHash}|${hello.sessionIdHash}|${hello.protocolVersion}".encodeToByteArray()
        val signature = hello.signature.decodeFromBase64()
        if (!signer.verify(publicKey, message, signature)) {
            throw IllegalArgumentException("Invalid signature on SESSION_HELLO")
        }
        return publicKey
    }

    fun createSessionConfirm(
        sharingIdentityId: dev.veilshare.core.model.SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: dev.veilshare.core.model.SessionId,
        receiverEphemeralKeyPair: X25519KeyPair,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionConfirm {
        val sharingIdentityIdHash = sha256(sharingIdentityId.value)
        val sessionIdHash = sha256(sessionId.value)
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

    fun verifySessionConfirm(
        confirm: dev.veilshare.core.model.SessionConfirm,
        expectedSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        signer: Ed25519Signer,
    ): Pair<Ed25519PublicKey, X25519PublicKey> {
        if (confirm.protocolVersion != dev.veilshare.core.model.SharingProtocol.VERSION) {
            throw IllegalArgumentException("Protocol version mismatch")
        }
        if (confirm.sharingIdentityIdHash != expectedSharingIdentityIdHash) {
            throw IllegalArgumentException("Sharing identity ID hash mismatch")
        }
        if (confirm.sessionIdHash != expectedSessionIdHash) {
            throw IllegalArgumentException("Session ID hash mismatch")
        }
        val sharingPublicKey = Ed25519PublicKey(confirm.sharingPublicKey.decodeFromBase64())
        val receiverEphemeralPublicKey = X25519PublicKey(confirm.receiverEphemeralPublicKey.decodeFromBase64())
        val message = "SESSION_CONFIRM|${confirm.sharingIdentityIdHash}|${confirm.sessionIdHash}|${confirm.receiverEphemeralPublicKey}|${confirm.protocolVersion}".encodeToByteArray()
        val signature = confirm.signature.decodeFromBase64()
        if (!signer.verify(sharingPublicKey, message, signature)) {
            throw IllegalArgumentException("Invalid signature on SESSION_CONFIRM")
        }
        return sharingPublicKey to receiverEphemeralPublicKey
    }

    fun createSessionConfirmAck(
        sessionId: dev.veilshare.core.model.SessionId,
        senderEphemeralKeyPair: X25519KeyPair,
        helloProtocolVersion: Int,
        helloSenderSharingIdentityIdHash: String,
        helloSessionIdHash: String,
        confirmProtocolVersion: Int,
        confirmReceiverSharingIdentityIdHash: String,
        confirmSessionIdHash: String,
        confirmEphemeralPublicKeyHash: String,
    ): dev.veilshare.core.model.SessionConfirmAck {
        val sessionIdHash = sha256(sessionId.value)
        val senderEphemeralPublicKeyHash = sha256(senderEphemeralKeyPair.publicKey.bytes)
        
        val transcript = HandshakeTranscript(
            helloProtocolVersion = helloProtocolVersion,
            helloSenderSharingIdentityIdHash = helloSenderSharingIdentityIdHash,
            helloSessionIdHash = helloSessionIdHash,
            confirmProtocolVersion = confirmProtocolVersion,
            confirmReceiverSharingIdentityIdHash = confirmReceiverSharingIdentityIdHash,
            confirmSessionIdHash = confirmSessionIdHash,
            confirmEphemeralPublicKeyHash = confirmEphemeralPublicKeyHash,
            ackProtocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            ackSessionIdHash = sessionIdHash,
            ackEphemeralPublicKeyHash = senderEphemeralPublicKeyHash,
            ackTranscriptHash = "",
        )
        
        val transcriptHash = transcript.sha256WithoutAckTranscriptHash()
        return dev.veilshare.core.model.SessionConfirmAck(
            sessionIdHash = sessionIdHash,
            senderEphemeralPublicKey = senderEphemeralKeyPair.publicKey.bytes.toBase64(),
            transcriptHash = transcriptHash,
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
        )
    }

    fun verifySessionConfirmAck(
        ack: dev.veilshare.core.model.SessionConfirmAck,
        expectedSessionIdHash: String,
        expectedSenderEphemeralPublicKeyHash: String,
        helloProtocolVersion: Int,
        helloSenderSharingIdentityIdHash: String,
        helloSessionIdHash: String,
        confirmProtocolVersion: Int,
        confirmReceiverSharingIdentityIdHash: String,
        confirmSessionIdHash: String,
        confirmEphemeralPublicKeyHash: String,
    ): X25519PublicKey {
        if (ack.protocolVersion != dev.veilshare.core.model.SharingProtocol.VERSION) {
            throw IllegalArgumentException("Protocol version mismatch")
        }
        if (ack.sessionIdHash != expectedSessionIdHash) {
            throw IllegalArgumentException("Session ID hash mismatch")
        }
        val senderEphemeralPublicKey = X25519PublicKey(ack.senderEphemeralPublicKey.decodeFromBase64())
        if (sha256(senderEphemeralPublicKey.bytes) != expectedSenderEphemeralPublicKeyHash) {
            throw IllegalArgumentException("Sender ephemeral public key hash mismatch")
        }
        
        val transcript = HandshakeTranscript(
            helloProtocolVersion = helloProtocolVersion,
            helloSenderSharingIdentityIdHash = helloSenderSharingIdentityIdHash,
            helloSessionIdHash = helloSessionIdHash,
            confirmProtocolVersion = confirmProtocolVersion,
            confirmReceiverSharingIdentityIdHash = confirmReceiverSharingIdentityIdHash,
            confirmSessionIdHash = confirmSessionIdHash,
            confirmEphemeralPublicKeyHash = confirmEphemeralPublicKeyHash,
            ackProtocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            ackSessionIdHash = ack.sessionIdHash,
            ackEphemeralPublicKeyHash = expectedSenderEphemeralPublicKeyHash,
            ackTranscriptHash = "",
        )
        
        val expectedTranscriptHash = transcript.sha256WithoutAckTranscriptHash()
        if (ack.transcriptHash != expectedTranscriptHash) {
            throw IllegalArgumentException("Transcript hash mismatch")
        }
        return senderEphemeralPublicKey
    }

    suspend fun deriveHandshakeKeys(
        senderEphemeralPrivateKey: X25519PrivateKey,
        receiverEphemeralPublicKey: X25519PublicKey,
        keyAgreement: X25519KeyAgreement,
        keyDeriver: KeyDeriver,
    ): HandshakeKeys = runBlocking {
        val sharedSecret = keyAgreement.deriveSharedSecret(senderEphemeralPrivateKey, receiverEphemeralPublicKey)
        val transcriptKey = deriveHkdf(keyDeriver, sharedSecret, TRANSCRIPT_BINDING_SALT.encodeToByteArray(), 32)
        val s2rKey = deriveHkdf(keyDeriver, sharedSecret, S2R_KEY_SALT.encodeToByteArray(), 32)
        val r2sKey = deriveHkdf(keyDeriver, sharedSecret, R2S_KEY_SALT.encodeToByteArray(), 32)
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

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
fun String.decodeFromBase64(): ByteArray = Base64.getDecoder().decode(this)