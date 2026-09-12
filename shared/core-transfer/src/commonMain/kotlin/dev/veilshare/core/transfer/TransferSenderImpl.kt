package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.TransferCancel
import dev.veilshare.core.model.TransferComplete
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// Match the peer codec's wire behavior so fragmentation size checks are reproducible.
private val jsonEncoder = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private inline fun <T> serializeToBytes(serializer: kotlinx.serialization.KSerializer<T>, value: T): ByteArray =
    jsonEncoder.encodeToString(serializer, value).encodeToByteArray()

class DefaultTransferSender(
    private val random: SecureRandom,
    private val config: TransferConfig = TransferConfig(),
) : TransferSender {

    override suspend fun send(
        transferId: TransferId,
        fileId: dev.veilshare.core.model.FileId,
        source: TransferSource,
        encryptor: TransferEncryptor,
        sender: TransferNetworkSender,
    ): TransferResult {
        val transferIdHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
        val fileIdHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray())
        val totalChunks = calculateTotalChunks(source.fileSize)

        require(totalChunks > 0) { "Empty files are not supported by Sharing V1" }
        require(source.fileSize <= config.maxTransferBytes) {
            "File too large: ${source.fileSize} bytes exceeds safe in-memory transfer cap ${config.maxTransferBytes}"
        }
        require(totalChunks <= config.maxChunks) {
            "File too large: $totalChunks chunks exceeds max ${config.maxChunks}"
        }

        var chunkIndex = 0
        var bytesSent = 0L

        try {
            while (chunkIndex < totalChunks) {
                val offset = chunkIndex.toLong() * config.chunkSize.toLong()
                val remainingBytes = source.fileSize - offset
                val chunkSize = minOf(config.chunkSize.toLong(), remainingBytes).toInt()
                val chunk = source.readChunk(offset, chunkSize)
                require(chunk.isNotEmpty()) { "Unexpected EOF while reading chunk $chunkIndex" }
                require(chunk.size <= config.chunkSize) {
                    "TransferSource returned ${chunk.size} bytes for max chunk size ${config.chunkSize}"
                }

                val nonce = TransferProtocol.createNonce(random)
                val aad = TransferProtocol.createDataAad(
                    protocolVersion = SharingProtocol.VERSION,
                    transferIdHash = transferIdHash,
                    fileIdHash = fileIdHash,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                )
                val ciphertext = encryptor.encrypt(chunk, nonce, aad)
                chunk.fill(0)
                require(ciphertext.isNotEmpty() && ciphertext.size <= config.maxCiphertextSize) {
                    "Encrypted chunk size ${ciphertext.size} exceeds max ${config.maxCiphertextSize}"
                }

                val transferData = TransferData(
                    transferIdHash = transferIdHash,
                    fileIdHash = fileIdHash,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    ciphertext = ciphertext,
                    nonce = nonce.bytes,
                )

                try {
                    sendWithFragmentation(transferData, sender)
                } finally {
                    ciphertext.fill(0)
                    nonce.bytes.fill(0)
                }
                bytesSent += chunkSize.toLong()
                chunkIndex++
            }

            completeWithRetry(transferIdHash, fileIdHash, totalChunks, sender)
            return TransferResult(totalChunks, bytesSent)
        } finally {
            withContext(NonCancellable) {
                source.close()
            }
        }
    }

    private suspend fun sendWithRetry(data: TransferData, sender: TransferNetworkSender) {
        retryTransport("chunk ${data.chunkIndex} fragment ${data.fragmentIndex + 1}/${data.fragmentCount}") {
            sender.send(data)
        }
    }

    private suspend fun sendWithFragmentation(data: TransferData, sender: TransferNetworkSender) {
        val fragmentCount = calculateFragmentCount(data)
        require(fragmentCount <= TransferProtocol.MAX_FRAGMENTS_PER_CHUNK) {
            "Chunk ${data.chunkIndex} requires $fragmentCount fragments, exceeds max ${TransferProtocol.MAX_FRAGMENTS_PER_CHUNK}"
        }

        if (fragmentCount == 1) {
            requireSerializedTransferDataFits(data)
            sendWithRetry(data, sender)
            return
        }

        val fragmentSize = (data.ciphertext.size + fragmentCount - 1) / fragmentCount
        for (i in 0 until fragmentCount) {
            val start = i * fragmentSize
            val end = minOf(start + fragmentSize, data.ciphertext.size)
            require(start < end) { "Fragmentation produced an empty fragment" }

            val fragmentCiphertext = data.ciphertext.copyOfRange(start, end)
            val fragment = data.copy(
                ciphertext = fragmentCiphertext,
                fragmentIndex = i,
                fragmentCount = fragmentCount,
            )
            try {
                requireSerializedTransferDataFits(fragment)
                sendWithRetry(fragment, sender)
            } finally {
                fragmentCiphertext.fill(0)
            }
        }
    }

    /**
     * Find the smallest practical fragment count whose largest serialized TransferData
     * fits the configured transport-frame budget. Do not clamp the required count.
     */
    private fun calculateFragmentCount(data: TransferData): Int {
        val maxPayload = config.maxTransportFramePayload
        val serialized = serializeToBytes(serializer<TransferData>(), data)
        if (serialized.size <= maxPayload) return 1

        var fragmentCount = maxOf(2, (serialized.size + maxPayload - 1) / maxPayload)
        while (fragmentCount <= TransferProtocol.MAX_FRAGMENTS_PER_CHUNK) {
            val fragmentSize = (data.ciphertext.size + fragmentCount - 1) / fragmentCount
            val sampleEnd = minOf(fragmentSize, data.ciphertext.size)
            val sampleCiphertext = data.ciphertext.copyOfRange(0, sampleEnd)
            val sample = data.copy(
                ciphertext = sampleCiphertext,
                fragmentIndex = 0,
                fragmentCount = fragmentCount,
            )
            val fits = try {
                serializeToBytes(serializer<TransferData>(), sample).size <= maxPayload
            } finally {
                sampleCiphertext.fill(0)
            }
            if (fits) return fragmentCount
            fragmentCount++
        }
        return fragmentCount
    }

    private fun requireSerializedTransferDataFits(data: TransferData) {
        val actual = serializeToBytes(serializer<TransferData>(), data).size
        require(actual <= config.maxTransportFramePayload) {
            "Serialized TransferData is $actual bytes, exceeds frame budget ${config.maxTransportFramePayload}"
        }
    }

    private suspend fun completeWithRetry(
        transferIdHash: String,
        fileIdHash: String,
        totalChunks: Int,
        sender: TransferNetworkSender,
    ) {
        retryTransport("COMPLETE") {
            sender.complete(transferIdHash, fileIdHash, totalChunks)
        }
    }

    private suspend fun retryTransport(operation: String, block: suspend () -> Unit) {
        var retries = 0
        var delayMs = config.baseRetryDelayMs.toDouble()

        while (true) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isRetryableTransportFailure(e)) throw e
                if (retries >= config.maxRetries) {
                    throw TransferException(
                        TransferError.IoError(
                            "Failed to send $operation after ${config.maxRetries} retries: ${e.message}",
                        ),
                        e,
                    )
                }
                retries++
                kotlinx.coroutines.delay(delayMs.toLong())
                delayMs = (delayMs * config.retryBackoffMultiplier)
                    .coerceAtMost(config.maxRetryDelayMs.toDouble())
            }
        }
    }

    private fun isRetryableTransportFailure(error: Throwable): Boolean = when (error) {
        is CancellationException -> false
        is SecurityException -> false
        is IllegalArgumentException -> false
        is TransferException -> error.error is TransferError.IoError
        else -> true
    }

    internal fun calculateTotalChunks(fileSize: Long): Int {
        require(fileSize >= 0) { "fileSize must be non-negative" }
        if (fileSize == 0L) return 0
        val chunks = ((fileSize - 1L) / config.chunkSize.toLong()) + 1L
        require(chunks <= Int.MAX_VALUE.toLong()) { "File requires too many chunks" }
        return chunks.toInt()
    }
}

class DefaultTransferEncryptor(
    private val cipher: AuthenticatedCipher,
    private val key: SensitiveBytes,
) : TransferEncryptor {
    override suspend fun encrypt(chunk: ByteArray, nonce: Nonce): ByteArray =
        encrypt(chunk, nonce, ByteArray(0))

    override suspend fun encrypt(chunk: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray =
        cipher.sealWithNonce(key, nonce, chunk, aad).ciphertext
}

class DefaultTransferDecryptor(
    private val cipher: AuthenticatedCipher,
    private val key: SensitiveBytes,
) : TransferDecryptor {
    override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray =
        decrypt(ciphertext, nonce, ByteArray(0))

    override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray =
        cipher.open(key, dev.veilshare.core.crypto.SealedBytes(nonce, ciphertext), aad)
}

/**
 * Production signaling adapter. It can only send through an already authenticated and
 * encrypted SecureSignalingPeerMessenger, so transfer metadata cannot bypass the outer
 * session-confidentiality layer.
 */
class SignalingTransferNetworkSender(
    private val messenger: SecureSignalingPeerMessenger,
) : TransferNetworkSender {
    override suspend fun send(data: TransferData) {
        messenger.send(DecodedPeerMessage.Data(data))
    }

    override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) {
        messenger.send(
            DecodedPeerMessage.Complete(
                TransferComplete(
                    transferIdHash = transferIdHash,
                    fileIdHash = fileIdHash,
                    totalChunks = totalChunks,
                ),
            ),
        )
    }

    override suspend fun cancel(transferIdHash: String, reason: String) {
        messenger.send(DecodedPeerMessage.Cancel(TransferCancel(transferIdHash, reason)))
    }
}

class ByteArrayTransferSource(
    private val data: ByteArray,
    override val displayName: String = "data",
    override val mimeHint: String? = "application/octet-stream",
) : TransferSource {
    override val fileSize: Long = data.size.toLong()

    override suspend fun readChunk(offset: Long, size: Int): ByteArray {
        require(offset >= 0)
        require(size >= 0)
        if (offset >= data.size.toLong() || size == 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(data.size, start + size)
        return data.copyOfRange(start, end)
    }

    override suspend fun close() {
        // No-op for caller-owned in-memory data.
    }
}
