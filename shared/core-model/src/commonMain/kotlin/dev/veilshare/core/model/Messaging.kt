package dev.veilshare.core.model

import kotlinx.serialization.Serializable

object SharingProtocol {
    const val VERSION = 1
    const val MAX_ENVELOPE_PAYLOAD_BYTES = 64 * 1024
    const val MAX_PEER_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun requireSupported(version: Int) {
        require(version == VERSION) { "Unsupported sharing protocol version" }
    }
}

@Serializable
data class SignalingEnvelope(
    val protocolVersion: Int,
    val messageId: MessageId,
    val type: MessageType,
    val sessionId: SessionId? = null,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(payload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) { "Envelope payload too large" }
    }

    companion object {
        fun create(
            type: MessageType,
            random: RandomBytesSource,
            sessionId: SessionId? = null,
            payload: ByteArray = ByteArray(0),
        ): SignalingEnvelope = SignalingEnvelope(
            protocolVersion = SharingProtocol.VERSION,
            messageId = OpaqueIds.messageId(random),
            type = type,
            sessionId = sessionId,
            payload = payload,
        )
    }
}

@Serializable
enum class MessageType {
    REGISTER,
    UNREGISTER,
    LOOKUP,
    RELAY,
    PING,
    ERROR,
}

@Serializable
enum class ErrorCode {
    PROTOCOL_VERSION_MISMATCH,
    INVALID_MESSAGE,
    INVALID_REFERENCE_CODE,
    RATE_LIMITED,
    NOT_AUTHENTICATED,
    SESSION_NOT_FOUND,
    DUPLICATE_SESSION,
}

@Serializable
data class ErrorMessage(
    val errorCode: ErrorCode,
    val details: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(details.length <= 512) { "Error details too large" }
    }
}

@Serializable
data class RegisterRequest(
    val sharingIdentityId: SharingIdentityId,
    val referenceCode: ReferenceCode,
    val sharingPublicKey: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(sharingPublicKey.isNotBlank() && sharingPublicKey.length <= 512)
    }
}

@Serializable
data class UnregisterRequest(
    val sharingIdentityId: SharingIdentityId,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
    }
}

@Serializable
data class LookupRequest(
    val referenceCode: ReferenceCode,
    val requestorSharingIdentityId: SharingIdentityId,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
    }
}

@Serializable
data class LookupResponse(
    val status: LookupStatus,
    val sharingIdentityId: SharingIdentityId? = null,
    val sharingPublicKey: String? = null,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        if (status == LookupStatus.FOUND) {
            require(sharingIdentityId != null)
            require(!sharingPublicKey.isNullOrBlank() && sharingPublicKey.length <= 512)
        }
        if (status != LookupStatus.FOUND) {
            require(sharingIdentityId == null && sharingPublicKey == null)
        }
    }
}

@Serializable
enum class LookupStatus {
    FOUND,
    NOT_FOUND,
    INVALID,
}

@Serializable
data class RelayRequest(
    val toReferenceCode: ReferenceCode,
    val sessionId: SessionId,
    val opaquePayload: ByteArray,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(opaquePayload.isNotEmpty()) { "Relay payload is required" }
        require(opaquePayload.size <= SharingProtocol.MAX_ENVELOPE_PAYLOAD_BYTES) { "Relay payload too large" }
    }
}

@Serializable
data class PingMessage(
    val timestampMillis: Long,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(timestampMillis >= 0)
    }
}

@Serializable
enum class PeerMessageType {
    SESSION_HELLO,
    SESSION_CONFIRM,
    OFFER,
    ACCEPT,
    REJECT,
    DATA,
    COMPLETE,
    CANCEL,
    FAILURE,
}

@Serializable
data class PeerEnvelope(
    val protocolVersion: Int,
    val messageType: PeerMessageType,
    val sessionId: SessionId,
    val transferId: TransferId,
    val payload: ByteArray,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(payload.size <= SharingProtocol.MAX_PEER_PAYLOAD_BYTES) { "Peer payload too large" }
    }
}

@Serializable
data class SessionHello(
    val sharingIdentityIdHash: String,
    val sharingPublicKey: String,
    val sessionIdHash: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
    val signature: String,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(sharingIdentityIdHash.isNotBlank() && sharingIdentityIdHash.length <= 128)
        require(sharingPublicKey.isNotBlank() && sharingPublicKey.length <= 512)
        require(sessionIdHash.isNotBlank() && sessionIdHash.length <= 128)
        require(signature.isNotBlank() && signature.length <= 512)
    }
}

@Serializable
data class SessionConfirm(
    val sharingIdentityIdHash: String,
    val sharingPublicKey: String,
    val sessionIdHash: String,
    val receiverEphemeralPublicKey: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
    val signature: String,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(sharingIdentityIdHash.isNotBlank() && sharingIdentityIdHash.length <= 128)
        require(sharingPublicKey.isNotBlank() && sharingPublicKey.length <= 512)
        require(sessionIdHash.isNotBlank() && sessionIdHash.length <= 128)
        require(receiverEphemeralPublicKey.isNotBlank() && receiverEphemeralPublicKey.length <= 512)
        require(signature.isNotBlank() && signature.length <= 512)
    }
}

@Serializable
data class SessionConfirmAck(
    val sessionIdHash: String,
    val senderEphemeralPublicKey: String,
    val transcriptHash: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(sessionIdHash.isNotBlank() && sessionIdHash.length <= 128)
        require(senderEphemeralPublicKey.isNotBlank() && senderEphemeralPublicKey.length <= 512)
        require(transcriptHash.isNotBlank() && transcriptHash.length <= 128)
    }
}

@Serializable
data class TransferData(
    val transferIdHash: String,
    val fileIdHash: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val fragmentIndex: Int = 0,
    val fragmentCount: Int = 1,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(transferIdHash.isNotBlank() && transferIdHash.length <= 128)
        require(fileIdHash.isNotBlank() && fileIdHash.length <= 128)
        require(chunkIndex >= 0)
        require(totalChunks > 0)
        require(chunkIndex < totalChunks)
        require(ciphertext.isNotEmpty() && ciphertext.size <= SharingProtocol.MAX_PEER_PAYLOAD_BYTES)
        require(nonce.size == 12) // ChaCha20-Poly1305 nonce
        require(fragmentIndex >= 0)
        require(fragmentCount > 0)
        require(fragmentIndex < fragmentCount)
    }
}

@Serializable
data class TransferComplete(
    val transferIdHash: String,
    val fileIdHash: String,
    val totalChunks: Int,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(transferIdHash.isNotBlank() && transferIdHash.length <= 128)
        require(fileIdHash.isNotBlank() && fileIdHash.length <= 128)
        require(totalChunks > 0)
    }
}

@Serializable
data class TransferCancel(
    val transferIdHash: String,
    val reason: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(transferIdHash.isNotBlank() && transferIdHash.length <= 128)
        require(reason.isNotBlank() && reason.length <= 512)
    }
}
