package dev.veilshare.core.model

import kotlinx.serialization.Serializable

/**
 * Public, pre-session-key carrier for the three authenticated handshake messages.
 *
 * Intentionally contains no TransferId. The signaling relay needs SessionId for routing,
 * but transfer identifiers and file metadata begin only after the session key exists.
 *
 * replyReferenceCode is routing metadata only. It is not an identity assertion and must
 * never replace pinned contact trust. A receiver may use it to reply within this session;
 * persistent route updates require a separately authenticated policy decision.
 */
@Serializable
enum class HandshakeMessageType {
    SESSION_HELLO,
    SESSION_CONFIRM,
    SESSION_CONFIRM_ACK,
}

@Serializable
data class HandshakePeerEnvelope(
    val protocolVersion: Int,
    val messageType: HandshakeMessageType,
    val sessionId: SessionId,
    @Serializable(with = Base64ByteArraySerializer::class)
    val payload: ByteArray,
    val replyReferenceCode: ReferenceCode? = null,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(payload.isNotEmpty()) { "Handshake payload is required" }
        require(payload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Handshake payload too large"
        }
    }
}

/**
 * Opaque post-handshake carrier placed inside RelayRequest.opaquePayload.
 *
 * The serialized plaintext PeerEnvelope (including message type, TransferId and all
 * OFFER/DATA/control metadata) is ChaCha20-Poly1305 encrypted before entering this DTO.
 * The signaling relay can see only version, nonce/ciphertext length and traffic timing.
 */
@Serializable
data class SecurePeerEnvelope(
    val protocolVersion: Int,
    @Serializable(with = Base64ByteArraySerializer::class)
    val nonce: ByteArray,
    @Serializable(with = Base64ByteArraySerializer::class)
    val ciphertext: ByteArray,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(nonce.size == 12) { "Secure peer envelope nonce must be 12 bytes" }
        require(ciphertext.isNotEmpty()) { "Secure peer envelope ciphertext is required" }
        // Exact serialized RelayRequest size is checked by the transport layer. This raw
        // cap prevents a hostile decoded object from allocating beyond the signaling bound.
        require(ciphertext.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) {
            "Secure peer envelope ciphertext too large"
        }
    }
}
