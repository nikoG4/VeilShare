package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

interface TransferDecryptor {
    suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray
}

interface TransferReceiver {
    suspend fun receive(transferData: TransferData): ReceiveResult
    fun getImportSource(transferId: TransferId, fileId: dev.veilshare.core.model.FileId): TransferImportSource
    fun getProgress(transferId: TransferId): StateFlow<TransferReceiverProgress?>
}

sealed interface ReceiveResult {
    data class ChunkAccepted(val chunkIndex: Int, val isFinal: Boolean) : ReceiveResult
    data class DuplicateChunk(val chunkIndex: Int) : ReceiveResult
    data class TransferComplete(val totalChunks: Int) : ReceiveResult
    data class Error(val error: TransferError) : ReceiveResult
}

sealed interface TransferReceiverProgress {
    data class ChunkReceived(val chunkIndex: Int, val totalChunks: Int, val bytesReceived: Long) : TransferReceiverProgress
    data class TransferComplete(val totalChunks: Int, val totalBytes: Long) : TransferReceiverProgress
    data class Error(val error: TransferError) : TransferReceiverProgress
}

interface TransferImportSource {
    val transferId: TransferId
    val fileId: dev.veilshare.core.model.FileId
    val displayName: String
    val mimeHint: String?
    val sizeHint: Long
    suspend fun openRead(): TransferImportReadHandle
    fun getProgress(): StateFlow<TransferImportProgress>
}

interface TransferImportReadHandle {
    suspend fun read(maxBytes: Int): ByteArray
    suspend fun close()
}

sealed interface TransferImportProgress {
    data class Preparing(val totalChunks: Int) : TransferImportProgress
    data class ChunkReady(val chunkIndex: Int, val totalChunks: Int) : TransferImportProgress
    data class Complete(val totalChunks: Int, val totalBytes: Long) : TransferImportProgress
    data class Error(val error: TransferError) : TransferImportProgress
}

class InMemoryTransferReceiver(
    private val decryptor: TransferDecryptor,
    private val config: TransferConfig = TransferConfig(),
) : TransferReceiver {

    private val activeTransfers = mutableMapOf<String, TransferState>()
    private val progressFlows = mutableMapOf<String, MutableStateFlow<TransferReceiverProgress?>>()

    override suspend fun receive(transferData: TransferData): ReceiveResult {
        val transferIdHash = transferData.transferIdHash
        val state = activeTransfers.getOrPut(transferIdHash) {
            TransferState(
                transferIdHash = transferIdHash,
                fileIdHash = transferData.fileIdHash,
                totalChunks = transferData.totalChunks,
                config = config,
                progressFlow = getOrCreateProgressFlow(transferIdHash),
            )
        }

        return state.processChunk(transferData, decryptor)
    }

    override fun getImportSource(transferId: TransferId, fileId: dev.veilshare.core.model.FileId): TransferImportSource {
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val state = activeTransfers.get(transferIdHash) ?: throw IllegalStateException("Transfer not found: $transferIdHash")
        return TransferImportSourceImpl(state, fileId)
    }

    override fun getProgress(transferId: TransferId): StateFlow<TransferReceiverProgress?> {
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        return getOrCreateProgressFlow(transferIdHash)
    }

    private fun getOrCreateProgressFlow(transferIdHash: String): MutableStateFlow<TransferReceiverProgress?> {
        return progressFlows.getOrPut(transferIdHash) { MutableStateFlow(null) }
    }

    private inner class TransferState(
        val transferIdHash: String,
        val fileIdHash: String,
        val totalChunks: Int,
        val config: TransferConfig,
        val progressFlow: MutableStateFlow<TransferReceiverProgress?>,
    ) {
        private val receivedChunks = BooleanArray(totalChunks) { false }
        private val chunkData = Array<ByteArray?>(totalChunks) { null }
        private var bytesReceived: Long = 0
        private var completed = false

        suspend fun processChunk(transferData: TransferData, decryptor: TransferDecryptor): ReceiveResult {
            if (completed) {
                return ReceiveResult.Error(TransferError.TransferCancelled("Transfer already completed"))
            }

            if (transferData.chunkIndex >= totalChunks) {
                return ReceiveResult.Error(TransferError.InvalidChunkIndex(totalChunks, transferData.chunkIndex))
            }

            if (receivedChunks[transferData.chunkIndex]) {
                return ReceiveResult.DuplicateChunk(transferData.chunkIndex)
            }

            val plaintext: ByteArray
            try {
                val nonce = Nonce(transferData.nonce)
                plaintext = decryptor.decrypt(transferData.ciphertext, nonce)
            } catch (e: Exception) {
                progressFlow.value = TransferReceiverProgress.Error(TransferError.DecryptionFailed(transferData.chunkIndex))
                return ReceiveResult.Error(TransferError.DecryptionFailed(transferData.chunkIndex))
            }

            receivedChunks[transferData.chunkIndex] = true
            chunkData[transferData.chunkIndex] = plaintext
            bytesReceived += plaintext.size.toLong()

            progressFlow.value = TransferReceiverProgress.ChunkReceived(
                chunkIndex = transferData.chunkIndex,
                totalChunks = totalChunks,
                bytesReceived = bytesReceived,
            )

            val isFinal = transferData.chunkIndex == totalChunks - 1
            val allReceived = receivedChunks.all { it }

            if (allReceived) {
                completed = true
                progressFlow.value = TransferReceiverProgress.TransferComplete(totalChunks, bytesReceived)
                return ReceiveResult.TransferComplete(totalChunks)
            }

            return ReceiveResult.ChunkAccepted(transferData.chunkIndex, isFinal)
        }

        fun getChunk(chunkIndex: Int): ByteArray? {
            return chunkData[chunkIndex]
        }

        fun isComplete(): Boolean = completed

        fun getTotalBytes(): Long = bytesReceived
    }

    private inner class TransferImportSourceImpl(
        private val state: TransferState,
        override val fileId: dev.veilshare.core.model.FileId,
    ) : TransferImportSource {
        override val transferId: TransferId = TransferId(state.transferIdHash)
        override val displayName: String = "received-file"
        override val mimeHint: String? = null
        override val sizeHint: Long = state.getTotalBytes()
        private var currentChunkIndex = 0
        private var closed = false

        override fun getProgress(): StateFlow<TransferImportProgress> {
            val flow = MutableStateFlow<TransferImportProgress>(TransferImportProgress.Preparing(state.totalChunks))
            
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                for (i in 0 until state.totalChunks) {
                    flow.value = TransferImportProgress.ChunkReady(i, state.totalChunks)
                    delay(1) // yield
                }
                flow.value = TransferImportProgress.Complete(state.totalChunks, state.getTotalBytes())
            }
            
            return flow
        }

        override suspend fun openRead(): TransferImportReadHandle {
            if (closed) throw IllegalStateException("Already closed")
            currentChunkIndex = 0
            return TransferImportReadHandleImpl()
        }

        private inner class TransferImportReadHandleImpl : TransferImportReadHandle {
            override suspend fun read(maxBytes: Int): ByteArray {
                while (currentChunkIndex < state.totalChunks) {
                    val chunk = state.getChunk(currentChunkIndex)
                    if (chunk != null) {
                        currentChunkIndex++
                        if (chunk.size <= maxBytes) {
                            return chunk
                        } else {
                            return chunk
                        }
                    }
                    delay(10)
                }
                return ByteArray(0) // EOF
            }

            override suspend fun close() {
                closed = true
            }
        }
    }
}