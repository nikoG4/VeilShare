package dev.veilshare.core.crypto

import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.model.SharingProtocol
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
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(senderIdentityIdHash.isNotBlank())
        require(receiverIdentityIdHash.isNotBlank())
        require(sessionIdHash.isNotBlank())
        require(senderEphemeralPublicKey.isNotBlank())
        require(receiverEphemeralPublicKey.isNotBlank())
    }

    fun toCanonicalBytes(): ByteArray = HandshakeCanonical.encode(
        domain = "VEILSHARE/HANDSHAKE/TRANSCRIPT/V1",
        protocolVersion = protocolVersion,
        fields = listOf(
            senderIdentityIdHash,
            receiverIdentityIdHash,
            sessionIdHash,
            senderEphemeralPublicKey,
            receiverEphemeralPublicKey,
        ),
    )

    fun computeTranscriptHash(): String = Hash.sha256(toCanonicalBytes()).toHex()
}

@Serializable
data class HandshakeKeys(
    val senderToReceiverKey: ByteArray,
    val receiverToSenderKey: ByteArray,
    val transcriptHash: String,
)

data class VerifiedSessionConfirmAck(
    val senderEphemeralPublicKey: X25519PublicKey,
    val transcript: HandshakeTranscript,
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
    )

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
    )

    /**
     * Creates the final handshake message. The sender ephemeral key and the complete
     * transcript are signed by the sender's long-term Ed25519 sharing identity.
     */
    fun createSessionConfirmAck(
        senderSharingIdentityId: SharingIdentityId,
        senderSharingKeyPair: Ed25519KeyPair,
        receiverSharingIdentityIdHash: String,
        sessionId: SessionId,
        senderEphemeralKeyPair: X25519KeyPair,
        receiverEphemeralPublicKey: X25519PublicKey,
        signer: Ed25519Signer,
    ): SessionConfirmAck

    /**
     * Verifies the ACK against the already trusted/pinned sender identity and rebuilds
     * the expected transcript using the sender ephemeral key carried by the signed ACK.
     */
    fun verifySessionConfirmAck(
        ack: SessionConfirmAck,
        expectedSenderSharingIdentityIdHash: String,
        expectedReceiverSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        expectedReceiverEphemeralPublicKey: X25519PublicKey,
        expectedSenderIdentityPublicKey: Ed25519PublicKey,
        signer: Ed25519Signer,
    ): VerifiedSessionConfirmAck

    /**
     * Derives both directional keys from X25519 and binds HKDF info to the authenticated
     * transcript. Both peers call this with their local private key + peer public key and
     * obtain the same directional key pair.
     */
    suspend fun deriveHandshakeKeys(
        localEphemeralPrivateKey: X25519PrivateKey,
        peerEphemeralPublicKey: X25519PublicKey,
        transcript: HandshakeTranscript,
        keyAgreement: X25519KeyAgreement,
        keyDeriver: KeyDeriver,
    ): HandshakeKeys
}

open class DefaultHandshakeProtocol : HandshakeProtocol {
    override fun createSessionHello(
        sharingIdentityId: SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: SessionId,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionHello {
        val identityHash = identityHash(sharingIdentityId)
        val sessionHash = sessionHash(sessionId)
        val message = HandshakeCanonical.helloSigningBytes(identityHash, sessionHash, SharingProtocol.VERSION)
        val signature = signer.sign(sharingKeyPair.privateKey, message)
        return dev.veilshare.core.model.SessionHello(
            sharingIdentityIdHash = identityHash,
            sharingPublicKey = sharingKeyPair.publicKey.bytes.toBase64(),
            sessionIdHash = sessionHash,
            protocolVersion = SharingProtocol.VERSION,
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
        SharingProtocol.requireSupported(hello.protocolVersion)
        require(hello.sharingIdentityIdHash == expectedSharingIdentityIdHash) { "Sharing identity ID hash mismatch" }
        require(hello.sessionIdHash == expectedSessionIdHash) { "Session ID hash mismatch" }

        val actualPublicKey = Ed25519PublicKey(hello.sharingPublicKey.decodeFromBase64())
        require(actualPublicKey.bytes.contentEquals(expectedSenderPublicKey.bytes)) {
            "Sender public key does not match expected peer identity"
        }

        val message = HandshakeCanonical.helloSigningBytes(
            hello.sharingIdentityIdHash,
            hello.sessionIdHash,
            hello.protocolVersion,
        )
        require(signer.verify(expectedSenderPublicKey, message, hello.signature.decodeFromBase64())) {
            "Invalid signature on SESSION_HELLO"
        }
    }

    override fun createSessionConfirm(
        sharingIdentityId: SharingIdentityId,
        sharingKeyPair: Ed25519KeyPair,
        sessionId: SessionId,
        receiverEphemeralKeyPair: X25519KeyPair,
        signer: Ed25519Signer,
    ): dev.veilshare.core.model.SessionConfirm {
        val identityHash = identityHash(sharingIdentityId)
        val sessionHash = sessionHash(sessionId)
        val ephemeral = receiverEphemeralKeyPair.publicKey.bytes.toBase64()
        val message = HandshakeCanonical.confirmSigningBytes(
            identityHash,
            sessionHash,
            ephemeral,
            SharingProtocol.VERSION,
        )
        val signature = signer.sign(sharingKeyPair.privateKey, message)
        return dev.veilshare.core.model.SessionConfirm(
            sharingIdentityIdHash = identityHash,
            sharingPublicKey = sharingKeyPair.publicKey.bytes.toBase64(),
            sessionIdHash = sessionHash,
            receiverEphemeralPublicKey = ephemeral,
            protocolVersion = SharingProtocol.VERSION,
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
        SharingProtocol.requireSupported(confirm.protocolVersion)
        require(confirm.sharingIdentityIdHash == expectedSharingIdentityIdHash) { "Sharing identity ID hash mismatch" }
        require(confirm.sessionIdHash == expectedSessionIdHash) { "Session ID hash mismatch" }

        val actualIdentityPublicKey = Ed25519PublicKey(confirm.sharingPublicKey.decodeFromBase64())
        require(actualIdentityPublicKey.bytes.contentEquals(expectedReceiverIdentityPublicKey.bytes)) {
            "Receiver identity public key does not match expected peer identity"
        }
        val actualEphemeralPublicKey = X25519PublicKey(confirm.receiverEphemeralPublicKey.decodeFromBase64())
        require(actualEphemeralPublicKey.bytes.contentEquals(expectedReceiverEphemeralPublicKey.bytes)) {
            "Receiver ephemeral public key does not match expected"
        }

        val message = HandshakeCanonical.confirmSigningBytes(
            confirm.sharingIdentityIdHash,
            confirm.sessionIdHash,
            confirm.receiverEphemeralPublicKey,
            confirm.protocolVersion,
        )
        require(signer.verify(expectedReceiverIdentityPublicKey, message, confirm.signature.decodeFromBase64())) {
            "Invalid signature on SESSION_CONFIRM"
        }
    }

    override fun createSessionConfirmAck(
        senderSharingIdentityId: SharingIdentityId,
        senderSharingKeyPair: Ed25519KeyPair,
        receiverSharingIdentityIdHash: String,
        sessionId: SessionId,
        senderEphemeralKeyPair: X25519KeyPair,
        receiverEphemeralPublicKey: X25519PublicKey,
        signer: Ed25519Signer,
    ): SessionConfirmAck {
        require(receiverSharingIdentityIdHash.isNotBlank())
        val senderIdentityHash = identityHash(senderSharingIdentityId)
        val sessionHash = sessionHash(sessionId)
        val senderEphemeral = senderEphemeralKeyPair.publicKey.bytes.toBase64()
        val receiverEphemeral = receiverEphemeralPublicKey.bytes.toBase64()
        val transcript = HandshakeTranscript(
            protocolVersion = SharingProtocol.VERSION,
            senderIdentityIdHash = senderIdentityHash,
            receiverIdentityIdHash = receiverSharingIdentityIdHash,
            sessionIdHash = sessionHash,
            senderEphemeralPublicKey = senderEphemeral,
            receiverEphemeralPublicKey = receiverEphemeral,
        )
        val transcriptHash = transcript.computeTranscriptHash()
        val signature = signer.sign(
            senderSharingKeyPair.privateKey,
            HandshakeCanonical.ackSigningBytes(
                senderIdentityHash = senderIdentityHash,
                sessionHash = sessionHash,
                senderEphemeralPublicKey = senderEphemeral,
                transcriptHash = transcriptHash,
                protocolVersion = SharingProtocol.VERSION,
            ),
        )
        return SessionConfirmAck(
            senderIdentityIdHash = senderIdentityHash,
            sessionIdHash = sessionHash,
            senderEphemeralPublicKey = senderEphemeral,
            transcriptHash = transcriptHash,
            protocolVersion = SharingProtocol.VERSION,
            signature = signature.toBase64(),
        )
    }

    override fun verifySessionConfirmAck(
        ack: SessionConfirmAck,
        expectedSenderSharingIdentityIdHash: String,
        expectedReceiverSharingIdentityIdHash: String,
        expectedSessionIdHash: String,
        expectedReceiverEphemeralPublicKey: X25519PublicKey,
        expectedSenderIdentityPublicKey: Ed25519PublicKey,
        signer: Ed25519Signer,
    ): VerifiedSessionConfirmAck {
        SharingProtocol.requireSupported(ack.protocolVersion)
        require(ack.senderIdentityIdHash == expectedSenderSharingIdentityIdHash) {
            "Sender sharing identity ID hash mismatch"
        }
        require(ack.sessionIdHash == expectedSessionIdHash) { "Session ID hash mismatch" }

        val senderEphemeralPublicKey = X25519PublicKey(ack.senderEphemeralPublicKey.decodeFromBase64())
        val transcript = HandshakeTranscript(
            protocolVersion = ack.protocolVersion,
            senderIdentityIdHash = expectedSenderSharingIdentityIdHash,
            receiverIdentityIdHash = expectedReceiverSharingIdentityIdHash,
            sessionIdHash = expectedSessionIdHash,
            senderEphemeralPublicKey = ack.senderEphemeralPublicKey,
            receiverEphemeralPublicKey = expectedReceiverEphemeralPublicKey.bytes.toBase64(),
        )
        val expectedTranscriptHash = transcript.computeTranscriptHash()
        require(ack.transcriptHash == expectedTranscriptHash) { "Transcript hash mismatch" }

        val signingBytes = HandshakeCanonical.ackSigningBytes(
            senderIdentityHash = ack.senderIdentityIdHash,
            sessionHash = ack.sessionIdHash,
            senderEphemeralPublicKey = ack.senderEphemeralPublicKey,
            transcriptHash = ack.transcriptHash,
            protocolVersion = ack.protocolVersion,
        )
        require(signer.verify(expectedSenderIdentityPublicKey, signingBytes, ack.signature.decodeFromBase64())) {
            "Invalid signature on SESSION_CONFIRM_ACK"
        }
        return VerifiedSessionConfirmAck(senderEphemeralPublicKey, transcript)
    }

    override suspend fun deriveHandshakeKeys(
        localEphemeralPrivateKey: X25519PrivateKey,
        peerEphemeralPublicKey: X25519PublicKey,
        transcript: HandshakeTranscript,
        keyAgreement: X25519KeyAgreement,
        keyDeriver: KeyDeriver,
    ): HandshakeKeys {
        val sharedSecret = keyAgreement.deriveSharedSecret(localEphemeralPrivateKey, peerEphemeralPublicKey)
        val transcriptHash = transcript.computeTranscriptHash()
        val s2rContext = HandshakeCanonical.keyDerivationContext("SENDER_TO_RECEIVER", transcriptHash)
        val r2sContext = HandshakeCanonical.keyDerivationContext("RECEIVER_TO_SENDER", transcriptHash)
        return try {
            val s2r = keyDeriver.derive(sharedSecret, s2rContext, 32)
            val r2s = keyDeriver.derive(sharedSecret, r2sContext, 32)
            try {
                HandshakeKeys(
                    senderToReceiverKey = s2r.copy(),
                    receiverToSenderKey = r2s.copy(),
                    transcriptHash = transcriptHash,
                )
            } finally {
                s2r.close()
                r2s.close()
            }
        } finally {
            sharedSecret.close()
            s2rContext.fill(0)
            r2sContext.fill(0)
        }
    }

    private fun identityHash(id: SharingIdentityId): String =
        Hash.sha256(id.value.encodeToByteArray()).toHex()

    private fun sessionHash(id: SessionId): String =
        Hash.sha256(id.value.encodeToByteArray()).toHex()
}

internal object HandshakeCanonical {
    fun helloSigningBytes(identityHash: String, sessionHash: String, protocolVersion: Int): ByteArray =
        encode(
            domain = "VEILSHARE/HANDSHAKE/SESSION_HELLO/V1",
            protocolVersion = protocolVersion,
            fields = listOf(identityHash, sessionHash),
        )

    fun confirmSigningBytes(
        identityHash: String,
        sessionHash: String,
        receiverEphemeralPublicKey: String,
        protocolVersion: Int,
    ): ByteArray = encode(
        domain = "VEILSHARE/HANDSHAKE/SESSION_CONFIRM/V1",
        protocolVersion = protocolVersion,
        fields = listOf(identityHash, sessionHash, receiverEphemeralPublicKey),
    )

    fun ackSigningBytes(
        senderIdentityHash: String,
        sessionHash: String,
        senderEphemeralPublicKey: String,
        transcriptHash: String,
        protocolVersion: Int,
    ): ByteArray = encode(
        domain = "VEILSHARE/HANDSHAKE/SESSION_CONFIRM_ACK/V1",
        protocolVersion = protocolVersion,
        fields = listOf(senderIdentityHash, sessionHash, senderEphemeralPublicKey, transcriptHash),
    )

    fun keyDerivationContext(label: String, transcriptHash: String): ByteArray =
        encode(
            domain = "VEILSHARE/HANDSHAKE/HKDF/V1",
            protocolVersion = SharingProtocol.VERSION,
            fields = listOf(label, transcriptHash),
        )

    fun encode(domain: String, protocolVersion: Int, fields: List<String>): ByteArray {
        val domainBytes = domain.encodeToByteArray()
        val encodedFields = fields.map { it.encodeToByteArray() }
        val totalSize = 4 + domainBytes.size + 4 + encodedFields.sumOf { 4 + it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        offset = writeLengthPrefixed(result, offset, domainBytes)
        offset = writeInt(result, offset, protocolVersion)
        for (field in encodedFields) {
            offset = writeLengthPrefixed(result, offset, field)
        }
        return result
    }

    private fun writeLengthPrefixed(out: ByteArray, offset: Int, value: ByteArray): Int {
        var cursor = writeInt(out, offset, value.size)
        value.copyInto(out, destinationOffset = cursor)
        cursor += value.size
        return cursor
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
        return offset + 4
    }
}

// expect/actual pattern for Hash and Base64
expect object Hash {
    fun sha256(input: ByteArray): ByteArray
}

expect fun ByteArray.toHex(): String

expect fun ByteArray.toBase64(): String

expect fun String.decodeFromBase64(): ByteArray

class HandshakeTranscriptEncodingException(message: String) : Exception(message)
