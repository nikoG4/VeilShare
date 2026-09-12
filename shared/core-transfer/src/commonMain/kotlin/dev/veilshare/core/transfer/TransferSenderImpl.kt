package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.RelayRequest
import dev.veilshare.core.model.TransferComplete
import dev.veilshare.core.model.TransferCancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

private val jsonEncoder = Json { ignoreUnknownKeys = true }

private inline fun <T> serializeToBytes(serializer: kotlinx.serialization.KSerializer<T>, value: T): ByteArray {
    return jsonEncoder.encodeToString(serializer, value).encodeToByteArray()
}

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
        require(totalChunks <= config.maxChunks) { "File too large: $totalChunks chunks exceeds max ${config.maxChunks}" }

        var chunkIndex = 0
        var bytesSent: Long = 0

        while (chunkIndex < totalChunks) {
            val offset = chunkIndex.toLong() * config.chunkSize
            val remainingBytes = source.fileSize - offset
            val chunkSize = minOf(config.chunkSize, remainingBytes.toInt())
            
            val chunk = source.readChunk(offset, chunkSize)
            val nonce = TransferProtocol.createNonce(random)
            val ciphertext = encryptor.encrypt(chunk, nonce)

            val transferData = TransferData(
                transferIdHash = transferIdHash,
                fileIdHash = fileIdHash,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                ciphertext = ciphertext,
                nonce = nonce.bytes,
            )

            sendWithRetry(transferData, sender)
            bytesSent += chunk.size.toLong()
            chunkIndex++
        }

        completeWithRetry(transferIdHash, fileIdHash, totalChunks, sender)
        source.close()

        return TransferResult(totalChunks, bytesSent)
    }

    private suspend fun sendWithRetry(data: TransferData, sender: TransferNetworkSender) {
        var attempt = 0
        var delayMs = config.baseRetryDelayMs.toDouble()
        
        while (true) {
            try {
                sender.send(data)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > config.maxRetries) {
                    throw TransferException(TransferError.IoError("Failed to send chunk ${data.chunkIndex} after ${config.maxRetries} retries: ${e.message}"), e)
                }
                kotlinx.coroutines.delay(delayMs.toLong())
                delayMs = (delayMs * config.retryBackoffMultiplier).coerceAtMost(config.maxRetryDelayMs.toDouble())
            }
        }
    }

    private suspend fun completeWithRetry(
        transferIdHash: String, 
        fileIdHash: String, 
        totalChunks: Int, 
        sender: TransferNetworkSender
    ) {
        var attempt = 0
        var delayMs = config.baseRetryDelayMs.toDouble()
        
        while (true) {
            try {
                sender.complete(transferIdHash, fileIdHash, totalChunks)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > config.maxRetries) {
                    throw TransferException(TransferError.IoError("Failed to send COMPLETE after ${config.maxRetries} retries: ${e.message}"), e)
                }
                kotlinx.coroutines.delay(delayMs.toLong())
                delayMs = (delayMs * config.retryBackoffMultiplier).coerceAtMost(config.maxRetryDelayMs.toDouble())
            }
        }
    }

    internal fun calculateTotalChunks(fileSize: Long): Int {
        return ((fileSize + config.chunkSize - 1) / config.chunkSize).toInt()
    }
}

class DefaultTransferEncryptor(
    private val cipher: AuthenticatedCipher,
    private val key: SensitiveBytes,
) : TransferEncryptor {

    override suspend fun encrypt(chunk: ByteArray, nonce: Nonce): ByteArray {
        return cipher.sealWithNonce(key, nonce, chunk, ByteArray(0)).ciphertext
    }
}

class DefaultTransferDecryptor(
    private val cipher: AuthenticatedCipher,
    private val key: SensitiveBytes,
) : TransferDecryptor {

    override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray {
        return cipher.open(key, dev.veilshare.core.crypto.SealedBytes(nonce, ciphertext), ByteArray(0))
    }
}

class SignalingTransferNetworkSender(
    private val signalingClient: dev.veilshare.core.platform.SignalingClient,
    private val sessionId: dev.veilshare.core.model.SessionId,
    private val transferId: TransferId,
    private val peerReferenceCode: dev.veilshare.core.model.ReferenceCode,
) : TransferNetworkSender {

    override suspend fun send(data: TransferData) {
        val envelope = PeerEnvelope(
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            messageType = dev.veilshare.core.model.PeerMessageType.DATA,
            sessionId = sessionId,
            transferId = transferId,
            payload = serializeToBytes(serializer<TransferData>(), data),
        )
        
        val relayRequest = RelayRequest(
            toReferenceCode = peerReferenceCode,
            sessionId = sessionId,
            opaquePayload = serializeToBytes(serializer<PeerEnvelope>(), envelope),
        )
        
        signalingClient.relay(relayRequest)
    }

    override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) {
        val complete = TransferComplete(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            totalChunks = totalChunks,
        )
        
        val envelope = PeerEnvelope(
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            messageType = dev.veilshare.core.model.PeerMessageType.COMPLETE,
            sessionId = sessionId,
            transferId = transferId,
            payload = serializeToBytes(serializer<TransferComplete>(), complete),
        )
        
        val relayRequest = RelayRequest(
            toReferenceCode = peerReferenceCode,
            sessionId = sessionId,
            opaquePayload = serializeToBytes(serializer<PeerEnvelope>(), envelope),
        )
        
        signalingClient.relay(relayRequest)
    }

    override suspend fun cancel(transferIdHash: String, reason: String) {
        val cancel = TransferCancel(
            transferIdHash = transferIdHash,
            reason = reason,
        )
        
        val envelope = PeerEnvelope(
            protocolVersion = dev.veilshare.core.model.SharingProtocol.VERSION,
            messageType = dev.veilshare.core.model.PeerMessageType.CANCEL,
            sessionId = sessionId,
            transferId = transferId,
            payload = serializeToBytes(serializer<TransferCancel>(), cancel),
        )
        
        val relayRequest = RelayRequest(
            toReferenceCode = peerReferenceCode,
            sessionId = sessionId,
            opaquePayload = serializeToBytes(serializer<PeerEnvelope>(), envelope),
        )
        
        signalingClient.relay(relayRequest)
    }
}

class FileTransferSource(
    private val file: java.io.File,
    override val displayName: String = file.name,
    override val mimeHint: String? = null,
) : TransferSource {

    private var randomAccessFile: java.io.RandomAccessFile? = null

    override val fileSize: Long = file.length()

    override suspend fun readChunk(offset: Long, size: Int): ByteArray {
        if (randomAccessFile == null) {
            randomAccessFile = java.io.RandomAccessFile(file, "r")
        }
        randomAccessFile?.let { it.seek(offset) }
        val buffer = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val read = randomAccessFile?.read(buffer, totalRead, size - totalRead) ?: -1
            if (read == -1) break
            totalRead += read
        }
        if (totalRead < size) {
            return buffer.copyOf(totalRead)
        }
        return buffer
    }

    override suspend fun close() {
        randomAccessFile?.close()
        randomAccessFile = null
    }
}

class ByteArrayTransferSource(
    private val data: ByteArray,
    override val displayName: String = "data",
    override val mimeHint: String? = "application/octet-stream",
) : TransferSource {

    override val fileSize: Long = data.size.toLong()

    override suspend fun readChunk(offset: Long, size: Int): ByteArray {
        val end = (offset + size).toInt().coerceAtMost(data.size)
        return data.copyOfRange(offset.toInt(), end)
    }

    override suspend fun close() {
        // No-op for in-memory source
    }
}