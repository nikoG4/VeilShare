package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

interface TransferDecryptor {
    suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray

    suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray {
        throw IllegalStateException("AAD-aware transfer decryption is required")
    }
}

interface TransferReceiver {
    suspend fun receive(transferData: TransferData): ReceiveResult
    suspend fun getImportSource(transferId: TransferId, fileId: FileId): TransferImportSource
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
    val fileId: FileId
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

enum class TransferStateEnum {
    NEW,
    RECEIVING,
    COMPLETE,
    CANCELLED,
    FAILED,
}

class InMemoryTransferReceiver(
    private val decryptor: TransferDecryptor,
    private val config: TransferConfig = TransferConfig(),
) : TransferReceiver {

    private val activeTransfers = mutableMapOf<String, TransferState>()
    private val mapMutex = newMutex()

    // Immutable-map snapshots make non-suspending getProgress() safe across platforms.
    private val progressRegistry =
        MutableStateFlow<Map<String, MutableStateFlow<TransferReceiverProgress?>>>(emptyMap())

    override suspend fun receive(transferData: TransferData): ReceiveResult {
        validateBeforeAllocation(transferData)?.let { return ReceiveResult.Error(it) }

        val transferIdHash = transferData.transferIdHash
        var capacityExceeded = false
        val state = mapMutex.withLock {
            activeTransfers[transferIdHash] ?: run {
                if (activeTransfers.size >= config.maxActiveTransfers) {
                    capacityExceeded = true
                    null
                } else {
                    TransferState(
                        transferIdHash = transferIdHash,
                        fileIdHash = transferData.fileIdHash,
                        protocolVersion = transferData.protocolVersion,
                        totalChunks = transferData.totalChunks,
                        progressFlow = getOrCreateProgressFlow(transferIdHash),
                    ).also { activeTransfers[transferIdHash] = it }
                }
            }
        }

        if (capacityExceeded || state == null) {
            return ReceiveResult.Error(TransferError.TooManyActiveTransfers(config.maxActiveTransfers))
        }

        // Per-transfer locking happens inside TransferState. Do not hold mapMutex while
        // decrypting or processing a chunk, otherwise one slow transfer blocks all peers.
        val result = state.processChunk(transferData, decryptor)
        if (result is ReceiveResult.Error && state.isFailed()) {
            cleanupTransfer(transferIdHash, state)
        }
        return result
    }

    override suspend fun getImportSource(transferId: TransferId, fileId: FileId): TransferImportSource {
        val transferIdHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
        val state = mapMutex.withLock {
            activeTransfers[transferIdHash]
        } ?: throw IllegalStateException("Transfer not found: $transferIdHash")

        check(state.isComplete()) { "Transfer is not complete" }

        val expectedFileIdHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray())
        check(state.fileIdHash == expectedFileIdHash) { "fileId does not match completed transfer" }

        return TransferImportSourceImpl(
            state = state,
            fileId = fileId,
            totalBytes = state.getTotalBytes(),
        )
    }

    override fun getProgress(transferId: TransferId): StateFlow<TransferReceiverProgress?> {
        val transferIdHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
        return getOrCreateProgressFlow(transferIdHash)
    }

    private fun validateBeforeAllocation(transferData: TransferData): TransferError? {
        if (transferData.totalChunks <= 0 || transferData.totalChunks > config.maxChunks) {
            return TransferError.InvalidTransferMetadata(
                "totalChunks ${transferData.totalChunks} outside allowed range 1..${config.maxChunks}",
            )
        }
        if (transferData.chunkIndex < 0 || transferData.chunkIndex >= transferData.totalChunks) {
            return TransferError.InvalidChunkIndex(transferData.totalChunks, transferData.chunkIndex)
        }
        if (transferData.ciphertext.isEmpty() || transferData.ciphertext.size > config.maxCiphertextSize) {
            return TransferError.ChunkTooLarge(config.maxCiphertextSize, transferData.ciphertext.size)
        }
        if (transferData.nonce.size != TransferProtocol.NONCE_SIZE) {
            return TransferError.InvalidTransferMetadata("Invalid nonce size ${transferData.nonce.size}")
        }
        return null
    }

    private fun getOrCreateProgressFlow(
        transferIdHash: String,
    ): MutableStateFlow<TransferReceiverProgress?> {
        progressRegistry.value[transferIdHash]?.let { return it }

        val candidate = MutableStateFlow<TransferReceiverProgress?>(null)
        progressRegistry.update { current ->
            if (current.containsKey(transferIdHash)) current else current + (transferIdHash to candidate)
        }
        return progressRegistry.value.getValue(transferIdHash)
    }

    private suspend fun cleanupTransfer(transferIdHash: String, state: TransferState) {
        mapMutex.withLock {
            if (activeTransfers[transferIdHash] === state) {
                activeTransfers.remove(transferIdHash)
            }
        }
        state.clearEncryptedChunks()
        progressRegistry.update { it - transferIdHash }
    }

    private data class EncryptedFragment(
        val ciphertext: ByteArray,
        val nonce: ByteArray,
        val fragmentIndex: Int,
        val fragmentCount: Int,
    )

    private inner class TransferState(
        val transferIdHash: String,
        val fileIdHash: String,
        val protocolVersion: Int,
        val totalChunks: Int,
        val progressFlow: MutableStateFlow<TransferReceiverProgress?>,
    ) {
        private val stateMutex = newMutex()
        private var state: TransferStateEnum = TransferStateEnum.NEW
        private val receivedChunks = BooleanArray(totalChunks)
        private val encryptedFragments = Array(totalChunks) { mutableListOf<EncryptedFragment>() }
        private var receivedCount = 0
        private var bytesReceived = 0L
        private var bufferedCiphertextBytes = 0L

        suspend fun processChunk(
            transferData: TransferData,
            decryptor: TransferDecryptor,
        ): ReceiveResult = stateMutex.withLock {
            when (state) {
                TransferStateEnum.COMPLETE ->
                    return@withLock ReceiveResult.Error(
                        TransferError.TransferCancelled("Transfer already completed"),
                    )
                TransferStateEnum.CANCELLED ->
                    return@withLock ReceiveResult.Error(
                        TransferError.TransferCancelled("Transfer cancelled"),
                    )
                TransferStateEnum.FAILED ->
                    return@withLock ReceiveResult.Error(TransferError.IoError("Transfer failed"))
                TransferStateEnum.NEW -> state = TransferStateEnum.RECEIVING
                TransferStateEnum.RECEIVING -> Unit
            }

            val metadataError = validateStateBinding(transferData)
            if (metadataError != null) {
                state = TransferStateEnum.FAILED
                progressFlow.value = TransferReceiverProgress.Error(metadataError)
                return@withLock ReceiveResult.Error(metadataError)
            }

            val chunkIndex = transferData.chunkIndex
            val fragmentIndex = transferData.fragmentIndex
            val fragmentCount = transferData.fragmentCount

            // Validate fragment bounds
            if (fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
                state = TransferStateEnum.FAILED
                val error = TransferError.InvalidTransferMetadata("Invalid fragmentIndex $fragmentIndex for fragmentCount $fragmentCount")
                progressFlow.value = TransferReceiverProgress.Error(error)
                return@withLock ReceiveResult.Error(error)
            }
            if (fragmentCount > TransferProtocol.MAX_FRAGMENTS_PER_CHUNK) {
                state = TransferStateEnum.FAILED
                val error = TransferError.InvalidTransferMetadata("fragmentCount $fragmentCount exceeds max ${TransferProtocol.MAX_FRAGMENTS_PER_CHUNK}")
                progressFlow.value = TransferReceiverProgress.Error(error)
                return@withLock ReceiveResult.Error(error)
            }

            val fragments = encryptedFragments[chunkIndex]

            // Check for duplicate fragment
            if (fragments.any { it.fragmentIndex == fragmentIndex }) {
                return@withLock ReceiveResult.DuplicateChunk(chunkIndex)
            }

            // If this is the first fragment for this chunk, validate chunk not already complete
            if (fragments.isEmpty() && receivedChunks[chunkIndex]) {
                return@withLock ReceiveResult.DuplicateChunk(chunkIndex)
            }

            val prospectiveBufferedBytes = bufferedCiphertextBytes + transferData.ciphertext.size.toLong()
            if (prospectiveBufferedBytes > config.maxTransferBytes) {
                state = TransferStateEnum.FAILED
                val error = TransferError.TransferTooLarge(
                    maxBytes = config.maxTransferBytes,
                    actualBytes = prospectiveBufferedBytes,
                )
                progressFlow.value = TransferReceiverProgress.Error(error)
                return@withLock ReceiveResult.Error(error)
            }

            // Store fragment
            fragments.add(EncryptedFragment(
                ciphertext = transferData.ciphertext.copyOf(),
                nonce = transferData.nonce.copyOf(),
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
            ))

            // Check if we have all fragments for this chunk
            if (fragments.size == fragmentCount) {
                // Reassemble and decrypt
                val aad = TransferProtocol.createDataAad(
                    protocolVersion = transferData.protocolVersion,
                    transferIdHash = transferData.transferIdHash,
                    fileIdHash = transferData.fileIdHash,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                )

                // Sort fragments by index and reassemble ciphertext
                fragments.sortBy { it.fragmentIndex }
                val totalSize = fragments.sumOf { it.ciphertext.size }
                val fullCiphertext = ByteArray(totalSize)
                var offset = 0
                for (f in fragments) {
                    f.ciphertext.copyInto(fullCiphertext, offset)
                    offset += f.ciphertext.size
                }
                val fullNonce = fragments[0].nonce // All fragments share the same nonce

                val plaintext = try {
                    decryptor.decrypt(
                        ciphertext = fullCiphertext,
                        nonce = Nonce(fullNonce),
                        aad = aad,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state = TransferStateEnum.FAILED
                    val error = TransferError.DecryptionFailed(chunkIndex)
                    progressFlow.value = TransferReceiverProgress.Error(error)
                    return@withLock ReceiveResult.Error(error)
                }

                // Authenticate immediately, but never retain plaintext in TransferState.
                val plaintextSize = plaintext.size
                plaintext.fill(0)

                // Replace fragments with the reassembled chunk for storage
                encryptedFragments[chunkIndex] = mutableListOf(EncryptedFragment(
                    ciphertext = fullCiphertext,
                    nonce = fullNonce,
                    fragmentIndex = 0,
                    fragmentCount = 1,
                ))

                receivedChunks[chunkIndex] = true
                receivedCount++
                bytesReceived += plaintextSize.toLong()
                bufferedCiphertextBytes = prospectiveBufferedBytes

                progressFlow.value = TransferReceiverProgress.ChunkReceived(
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    bytesReceived = bytesReceived,
                )

                val isFinal = chunkIndex == totalChunks - 1
                if (receivedCount == totalChunks) {
                    state = TransferStateEnum.COMPLETE
                    progressFlow.value =
                        TransferReceiverProgress.TransferComplete(totalChunks, bytesReceived)
                    return@withLock ReceiveResult.TransferComplete(totalChunks)
                }

                ReceiveResult.ChunkAccepted(chunkIndex, isFinal)
            } else {
                // Fragment received, but waiting for more
                bufferedCiphertextBytes = prospectiveBufferedBytes
                ReceiveResult.ChunkAccepted(chunkIndex, false)
            }
        }

        private fun validateStateBinding(transferData: TransferData): TransferError? {
            if (transferData.transferIdHash != transferIdHash) {
                return TransferError.InvalidTransferMetadata("transferIdHash changed during transfer")
            }
            if (transferData.fileIdHash != fileIdHash) {
                return TransferError.InvalidTransferMetadata("fileIdHash changed during transfer")
            }
            if (transferData.protocolVersion != protocolVersion) {
                return TransferError.InvalidTransferMetadata("protocolVersion changed during transfer")
            }
            if (transferData.totalChunks != totalChunks) {
                return TransferError.InvalidTransferMetadata("totalChunks changed during transfer")
            }
            if (transferData.chunkIndex < 0 || transferData.chunkIndex >= totalChunks) {
                return TransferError.InvalidChunkIndex(totalChunks, transferData.chunkIndex)
            }
            return null
        }

        suspend fun getEncryptedChunk(chunkIndex: Int): EncryptedFragment? =
            stateMutex.withLock {
                val fragments = encryptedFragments[chunkIndex]
                if (fragments.isEmpty()) return@withLock null
                // Return the reassembled fragment (fragmentCount should be 1 after reassembly)
                fragments.firstOrNull()
            }

        suspend fun isComplete(): Boolean =
            stateMutex.withLock { state == TransferStateEnum.COMPLETE }

        suspend fun isFailed(): Boolean =
            stateMutex.withLock { state == TransferStateEnum.FAILED }

        suspend fun getTotalBytes(): Long = stateMutex.withLock { bytesReceived }

        suspend fun clearEncryptedChunks() = stateMutex.withLock {
            for (i in encryptedFragments.indices) {
                encryptedFragments[i].clear()
                receivedChunks[i] = false
            }
            receivedCount = 0
            bufferedCiphertextBytes = 0L
        }
    }

    private inner class TransferImportSourceImpl(
        private val state: TransferState,
        override val fileId: FileId,
        private val totalBytes: Long,
    ) : TransferImportSource {
        override val transferId: TransferId = TransferId(state.transferIdHash)
        override val displayName: String = "received-file"
        override val mimeHint: String? = null
        override val sizeHint: Long = totalBytes

        private val sourceMutex = newMutex()
        private var opened = false
        private val importProgress =
            MutableStateFlow<TransferImportProgress>(TransferImportProgress.Preparing(state.totalChunks))

        override fun getProgress(): StateFlow<TransferImportProgress> = importProgress

        override suspend fun openRead(): TransferImportReadHandle = sourceMutex.withLock {
            check(!opened) { "Transfer import source can only be opened once" }
            opened = true
            TransferImportReadHandleImpl()
        }

        private inner class TransferImportReadHandleImpl : TransferImportReadHandle {
            private val readMutex = newMutex()
            private var currentChunkIndex = 0
            private var currentPlaintext: ByteArray? = null
            private var currentOffset = 0
            private var closed = false
            private var cleanedUp = false

            override suspend fun read(maxBytes: Int): ByteArray = readMutex.withLock {
                require(maxBytes > 0) { "maxBytes must be positive" }
                check(!closed) { "Read handle is closed" }

                while (true) {
                    val plaintext = currentPlaintext
                    if (plaintext != null && currentOffset < plaintext.size) {
                        val end = minOf(plaintext.size, currentOffset + maxBytes)
                        val result = plaintext.copyOfRange(currentOffset, end)
                        currentOffset = end
                        if (currentOffset == plaintext.size) {
                            plaintext.fill(0)
                            currentPlaintext = null
                            currentOffset = 0
                            currentChunkIndex++
                        }
                        return@withLock result
                    }

                    if (currentChunkIndex >= state.totalChunks) {
                        importProgress.value =
                            TransferImportProgress.Complete(state.totalChunks, totalBytes)
                        cleanupOnce()
                        return@withLock ByteArray(0)
                    }

                    val encrypted = state.getEncryptedChunk(currentChunkIndex)
                        ?: throw IllegalStateException(
                            "Missing encrypted chunk $currentChunkIndex from completed transfer",
                        )
                    val aad = TransferProtocol.createDataAad(
                        protocolVersion = state.protocolVersion,
                        transferIdHash = state.transferIdHash,
                        fileIdHash = state.fileIdHash,
                        chunkIndex = currentChunkIndex,
                        totalChunks = state.totalChunks,
                    )
                    currentPlaintext = try {
                        decryptor.decrypt(
                            ciphertext = encrypted.ciphertext,
                            nonce = Nonce(encrypted.nonce),
                            aad = aad,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val error = TransferError.DecryptionFailed(currentChunkIndex)
                        importProgress.value = TransferImportProgress.Error(error)
                        cleanupOnce()
                        throw TransferException(error, e)
                    }
                    currentOffset = 0
                    importProgress.value =
                        TransferImportProgress.ChunkReady(currentChunkIndex, state.totalChunks)
                }
                // Unreachable, but satisfies compiler return type analysis
                return@withLock ByteArray(0)
            }

            override suspend fun close() {
                readMutex.withLock {
                    if (closed) return@withLock
                    currentPlaintext?.fill(0)
                    currentPlaintext = null
                    currentOffset = 0
                    closed = true
                    cleanupOnce()
                }
            }

            private suspend fun cleanupOnce() {
                if (cleanedUp) return
                cleanedUp = true
                cleanupTransfer(state.transferIdHash, state)
            }
        }
    }
}
