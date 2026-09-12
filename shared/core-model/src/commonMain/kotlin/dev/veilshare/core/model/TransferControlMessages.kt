package dev.veilshare.core.model

import kotlinx.serialization.Serializable

/**
 * E2E-only metadata for a single-file Sharing V1 transfer.
 *
 * The signaling server only relays the serialized peer envelope and must never inspect
 * these fields. File metadata is validated at the protocol boundary before it reaches UI
 * or the vault import adapter.
 */
@Serializable
data class TransferOffer(
    val fileId: FileId,
    val displayName: String,
    val mimeHint: String? = null,
    val sizeBytes: Long,
    val totalChunks: Int,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        requireCanonicalDisplayName(displayName)
        requireCanonicalMimeHint(mimeHint)
        require(sizeBytes > 0) { "Sharing V1 does not support empty files" }
        require(totalChunks > 0) { "totalChunks must be positive" }
    }
}

@Serializable
data class TransferAccept(
    val fileId: FileId,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
    }
}

@Serializable
data class TransferReject(
    val fileId: FileId,
    val reason: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        requireCanonicalReason(reason, "reject reason")
    }
}

@Serializable
enum class TransferFailureCode {
    PROTOCOL_ERROR,
    AUTHENTICATION_FAILED,
    DECRYPTION_FAILED,
    LIMIT_EXCEEDED,
    TRANSFER_EXPIRED,
    CANCELLED,
    IO_ERROR,
    INTERNAL_ERROR,
}

@Serializable
data class TransferFailure(
    val transferIdHash: String,
    val code: TransferFailureCode,
    val details: String,
    val protocolVersion: Int = SharingProtocol.VERSION,
) {
    init {
        SharingProtocol.requireSupported(protocolVersion)
        require(transferIdHash.isNotBlank() && transferIdHash.length <= 128) {
            "transferIdHash is invalid"
        }
        requireCanonicalReason(details, "failure details")
    }
}

internal fun requireCanonicalDisplayName(value: String) {
    require(value.isNotBlank()) { "displayName is required" }
    require(value == value.trim()) { "displayName must be canonical (no surrounding whitespace)" }
    require(value.length <= SharingMetadataLimits.MAX_DISPLAY_NAME_CHARS) { "displayName is too long" }
    require(value.none { it == '/' || it == '\\' || it.isUnsafeControlCharacter() }) {
        "displayName contains unsafe path/control characters"
    }
}

internal fun requireCanonicalMimeHint(value: String?) {
    if (value == null) return
    require(value.isNotBlank()) { "mimeHint must be null or non-blank" }
    require(value == value.trim()) { "mimeHint must be canonical (no surrounding whitespace)" }
    require(value.length <= SharingMetadataLimits.MAX_MIME_HINT_CHARS) { "mimeHint is too long" }
    require(value.none { it.isUnsafeControlCharacter() }) { "mimeHint contains control characters" }
}

private fun requireCanonicalReason(value: String, field: String) {
    require(value.isNotBlank()) { "$field is required" }
    require(value == value.trim()) { "$field must be canonical (no surrounding whitespace)" }
    require(value.length <= SharingMetadataLimits.MAX_REASON_CHARS) { "$field is too long" }
    require(value.none { it.isUnsafeControlCharacter() }) { "$field contains control characters" }
}

private fun Char.isUnsafeControlCharacter(): Boolean = code < 0x20 || code == 0x7f

object SharingMetadataLimits {
    const val MAX_DISPLAY_NAME_CHARS = 255
    const val MAX_MIME_HINT_CHARS = 255
    const val MAX_REASON_CHARS = 512
}
