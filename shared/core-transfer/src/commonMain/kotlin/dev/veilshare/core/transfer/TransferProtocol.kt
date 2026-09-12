package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.TransferId

object TransferProtocol {
    const val CHUNK_SIZE = 1_048_576 // 1 MiB
    const val NONCE_SIZE = 12
    const val MAX_CHUNKS = 65_536
    const val MAX_ACTIVE_TRANSFERS = 2
    const val MAX_BUFFERED_CIPHERTEXT_BYTES = 64L * 1024L * 1024L
    const val AEAD_OVERHEAD_ALLOWANCE = 64
    const val MAX_FRAGMENTS_PER_CHUNK = 16
    private const val DATA_AAD_DIRECTION_SENDER_TO_RECEIVER = 1
    private const val DATA_AAD_DOMAIN = "VEILSHARE-DATA-AAD-V1"

    /**
     * Maximum payload size for a single transport frame (TransferData + PeerEnvelope + RelayRequest JSON overhead).
     * Leaves headroom for JSON framing and base64 encoding expansion (~33%).
     */
    const val MAX_TRANSPORT_FRAME_PAYLOAD = 48 * 1024 // 48 KiB

    fun createNonce(random: SecureRandom): Nonce = Nonce(random.bytes(NONCE_SIZE))

    /**
     * Canonical binary AAD for DATA frames.
     *
     * Session separation is provided by the per-session transfer key derived by the
     * authenticated handshake. This AAD binds all mutable DATA metadata inside that
     * session so ciphertext cannot be moved between transfers/files/chunk positions.
     */
    fun createDataAad(
        protocolVersion: Int,
        transferIdHash: String,
        fileIdHash: String,
        chunkIndex: Int,
        totalChunks: Int,
    ): ByteArray {
        require(protocolVersion == SharingProtocol.VERSION) { "Unsupported transfer protocol version" }
        require(transferIdHash.isNotBlank()) { "transferIdHash is required" }
        require(fileIdHash.isNotBlank()) { "fileIdHash is required" }
        require(chunkIndex >= 0) { "chunkIndex must be non-negative" }
        require(totalChunks > 0) { "totalChunks must be positive" }
        require(chunkIndex < totalChunks) { "chunkIndex must be smaller than totalChunks" }

        val domain = DATA_AAD_DOMAIN.encodeToByteArray()
        val transfer = transferIdHash.encodeToByteArray()
        val file = fileIdHash.encodeToByteArray()

        val out = ByteArray(
            4 + domain.size +
                4 + // protocolVersion
                4 + // direction
                4 + transfer.size +
                4 + file.size +
                4 + // chunkIndex
                4,  // totalChunks
        )
        var offset = 0
        offset = writeLengthPrefixed(out, offset, domain)
        offset = writeInt(out, offset, protocolVersion)
        offset = writeInt(out, offset, DATA_AAD_DIRECTION_SENDER_TO_RECEIVER)
        offset = writeLengthPrefixed(out, offset, transfer)
        offset = writeLengthPrefixed(out, offset, file)
        offset = writeInt(out, offset, chunkIndex)
        writeInt(out, offset, totalChunks)
        return out
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

data class TransferConfig(
    val chunkSize: Int = TransferProtocol.CHUNK_SIZE,
    val maxChunks: Int = TransferProtocol.MAX_CHUNKS,
    val maxActiveTransfers: Int = TransferProtocol.MAX_ACTIVE_TRANSFERS,
    val maxCiphertextSize: Int = chunkSize + TransferProtocol.AEAD_OVERHEAD_ALLOWANCE,
    val maxTransferBytes: Long = TransferProtocol.MAX_BUFFERED_CIPHERTEXT_BYTES,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 500,
    val maxRetryDelayMs: Long = 10_000,
    val retryBackoffMultiplier: Double = 2.0,
    val maxTransportFramePayload: Int = TransferProtocol.MAX_TRANSPORT_FRAME_PAYLOAD,
) {
    init {
        require(chunkSize > 0)
        require(maxChunks > 0)
        require(maxActiveTransfers > 0)
        require(maxCiphertextSize > 0)
        require(maxTransferBytes > 0)
        require(maxRetries >= 0)
        require(baseRetryDelayMs >= 0)
        require(maxRetryDelayMs >= baseRetryDelayMs)
        require(retryBackoffMultiplier >= 1.0)
        require(maxTransportFramePayload > 0)
    }
}

sealed interface TransferError {
    data class ChunkTooLarge(val maxSize: Int, val actualSize: Int) : TransferError
    data class InvalidChunkIndex(val expected: Int, val received: Int) : TransferError
    data class InvalidTransferMetadata(val reason: String) : TransferError
    data class TooManyActiveTransfers(val maxActiveTransfers: Int) : TransferError
    data class TransferTooLarge(val maxBytes: Long, val actualBytes: Long) : TransferError
    data class DecryptionFailed(val chunkIndex: Int) : TransferError
    data class DuplicateChunk(val chunkIndex: Int) : TransferError
    data class TransferCancelled(val reason: String) : TransferError
    data class IoError(val message: String) : TransferError
}

class TransferException(val error: TransferError, cause: Throwable? = null) : Exception(error.toString(), cause)

interface TransferSender {
    suspend fun send(
        transferId: TransferId,
        fileId: dev.veilshare.core.model.FileId,
        source: TransferSource,
        encryptor: TransferEncryptor,
        sender: TransferNetworkSender,
    ): TransferResult
}

interface TransferSource {
    val fileSize: Long
    val displayName: String
    val mimeHint: String?
    suspend fun readChunk(offset: Long, size: Int): ByteArray
    suspend fun close()
}

interface TransferEncryptor {
    suspend fun encrypt(chunk: ByteArray, nonce: Nonce): ByteArray

    suspend fun encrypt(chunk: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray {
        throw IllegalStateException("AAD-aware transfer encryption is required")
    }
}

interface TransferNetworkSender {
    suspend fun send(data: dev.veilshare.core.model.TransferData)
    suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int)
    suspend fun cancel(transferIdHash: String, reason: String)
}

data class TransferResult(
    val totalChunks: Int,
    val totalBytes: Long,
)

sealed interface TransferProgress {
    data class ChunkSent(val chunkIndex: Int, val totalChunks: Int, val bytesSent: Long) : TransferProgress
    data class ChunkAcknowledged(val chunkIndex: Int, val totalChunks: Int) : TransferProgress
    data class TransferComplete(val totalChunks: Int, val totalBytes: Long) : TransferProgress
}
