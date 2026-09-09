package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId

object TransferProtocol {
    const val CHUNK_SIZE = 1_048_576 // 1 MiB
    const val NONCE_SIZE = 12
    const val MAX_CHUNKS = 1_000_000

    fun createNonce(random: SecureRandom): Nonce {
        return Nonce(random.bytes(NONCE_SIZE))
    }
}

data class TransferConfig(
    val chunkSize: Int = TransferProtocol.CHUNK_SIZE,
    val maxChunks: Int = TransferProtocol.MAX_CHUNKS,
)

sealed interface TransferError {
    data class ChunkTooLarge(val maxSize: Int, val actualSize: Int) : TransferError
    data class InvalidChunkIndex(val expected: Int, val received: Int) : TransferError
    data class DecryptionFailed(val chunkIndex: Int) : TransferError
    data class DuplicateChunk(val chunkIndex: Int) : TransferError
    data class TransferCancelled(val reason: String) : TransferError
    data class IoError(val message: String) : TransferError
}

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
}

interface TransferNetworkSender {
    suspend fun send(data: TransferData)
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