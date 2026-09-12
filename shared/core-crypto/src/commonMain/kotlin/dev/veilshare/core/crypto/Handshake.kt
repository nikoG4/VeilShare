package dev.veilshare.core.crypto

import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.serialization.Serializable

@Serializable
data class HandshakeTranscript(
    val protocolVersion: Int,
    val senderIdentityIdHash: String,
    val receiverIdentityIdHash: String,
    val sessionIdHash: String,
    val senderEphemeralPublicKey: String,
    val receiverEphemeralPublicKey: String,
) {
    fun toCanonicalBytes(): ByteArray {
        // Canonical binary encoding: version(4) + string fields with length prefix
        val fields = listOf(
            senderIdentityIdHash,
            receiverIdentityIdHash,
            sessionIdHash,
            senderEphemeralPublicKey,
            receiverEphemeralPublicKey
        )
        val totalSize = 4 + fields.sumOf { 4 + it.encodeToByteArray().size }
        val result = ByteArray(totalSize)
        var offset = 0
        // protocol version (4 bytes, big-endian)
        result[offset] = (protocolVersion shr 24).toByte()
        result[offset + 1] = (protocolVersion shr 16).toByte()
        result[offset + 2] = (protocolVersion shr 8).toByte()
        result[offset + 3] = (protocolVersion and 0xFF).toByte()
        offset += 4
        for (field in fields) {
            val bytes = field.encodeToByteArray()
            result[offset] = (bytes.size shr 24).toByte()
            result[offset + 1] = (bytes.size shr 16).toByte()
            result[offset + 2] = (bytes.size shr 8).toByte()
            result[offset + 3] = (bytes.size and 0xFF).toByte()
            offset += 4
            bytes.copyInto(result, offset)
            offset += bytes.size
        }
        return result
    }
    
    fun computeTranscriptHash(): String = Hash.sha256(toCanonicalBytes()).toHex()
}

@Serializable
data class HandshakeKeys(
    val senderToReceiverKey: ByteArray,
    val receiverToSenderKey: ByteArray,
    val transcriptHash: String,
)

interface HandshakeProtocol {
    fun createSessionHello(
        sharingIdentityId: SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: SessionId,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionHello

    fun verifySessionHello(
        hello: dev.veilshare.core.model.SessionHello,
        expectedSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        expectedSenderPublicKey: Ed25519PublicKey,
        signer: Ed25519Signer,
    ): Unit

    fun createSessionConfirm(
        sharingIdentityId: SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: SessionId,
        receiverEphemeralKeyPair: X25519KeyPair,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionConfirm

    fun verifySessionConfirm(
        confirm: dev.veilshare.core.model.SessionConfirm,
        expectedSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        expectedReceiverEphemeralPublicKey: X25519PublicKey,
        expectedReceiverIdentityPublicKey: Ed25519PublicKey,
        signer: Ed25519Signer,
    ): Unit

    fun createSessionConfirmAck(
        sessionId: SessionId,
        senderEphemeralKeyPair: X25519KeyPair,
        transcript: HandshakeTranscript,
    ): dev.veilshare.core.model.SessionConfirmAck

    fun verifySessionConfirmAck(
        ack: dev.veilshare.core.model.SessionConfirmAck,
        expectedSessionIdHash: String,
        expectedSenderEphemeralPublicKeyHash: String,
        transcript: HandshakeTranscript,
    ): X25519PublicKey

    suspend fun deriveHandshakeKeys(
        senderEphemeralPrivateKey: X25519PrivateKey,
        receiverEphemeralPublicKey: X25519PublicKey,
        keyAgreement: X25519KeyAgreement,
        keyDeriver: KeyDeriver,
    ): HandshakeKeys
}

// expect/actual pattern for Hash and Base64
expect object Hash {
    fun sha256(input: ByteArray): ByteArray
}

expect fun ByteArray.toHex(): String

expect fun ByteArray.toBase64(): String

expect fun String.decodeFromBase64(): ByteArray

class HandshakeTranscriptEncodingException(message: String) : Exception(message)