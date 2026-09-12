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
        // authenticating or processing a chunk, otherwise one slow transfer blocks all peers.
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
            transferId = transferId,
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
        if (transferData.fragmentCount <= 0 || transferData.fragmentCount > TransferProtocol.MAX_FRAGMENTS_PER_CHUNK) {
            return TransferError.InvalidTransferMetadata(
                "fragmentCount ${transferData.fragmentCount} outside allowed range 1..${TransferProtocol.MAX_FRAGMENTS_PER_CHUNK}",
            )
        }
        if (transferData.fragmentIndex < 0 || transferData.fragmentIndex >= transferData.fragmentCount) {
            return TransferError.InvalidTransferMetadata(
                "Invalid fragmentIndex ${transferData.fragmentIndex} for fragmentCount ${transferData.fragmentCount}",
            )
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
                return@withLock fail(metadataError)
            }

            val chunkIndex = transferData.chunkIndex
            val fragmentIndex = transferData.fragmentIndex
            val fragmentCount = transferData.fragmentCount
            val fragments = encryptedFragments[chunkIndex]

            if (receivedChunks[chunkIndex]) {
                return@withLock ReceiveResult.DuplicateChunk(chunkIndex)
            }

            if (fragments.any { it.fragmentIndex == fragmentIndex }) {
                return@withLock ReceiveResult.DuplicateChunk(chunkIndex)
            }

            // Fragment metadata is transport state, not AEAD AAD. It therefore must be
            // internally consistent before reassembly or an attacker could force ambiguous
            // grouping / premature assembly of otherwise authenticated ciphertext.
            if (fragments.isNotEmpty()) {
                val first = fragments.first()
                if (first.fragmentCount != fragmentCount) {
                    return@withLock fail(
                        TransferError.InvalidTransferMetadata(
                            "fragmentCount changed within chunk $chunkIndex: ${first.fragmentCount} -> $fragmentCount",
                        ),
                    )
                }
                if (!first.nonce.contentEquals(transferData.nonce)) {
                    return@withLock fail(
                        TransferError.InvalidTransferMetadata("nonce changed between fragments of chunk $chunkIndex"),
                    )
                }
            }

            val chunkBufferedBytes = fragments.sumOf { it.ciphertext.size.toLong() } + transferData.ciphertext.size.toLong()
            if (chunkBufferedBytes > config.maxCiphertextSize.toLong()) {
                return@withLock fail(
                    TransferError.ChunkTooLarge(config.maxCiphertextSize, chunkBufferedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                )
            }

            val prospectiveBufferedBytes = bufferedCiphertextBytes + transferData.ciphertext.size.toLong()
            if (prospectiveBufferedBytes > config.maxTransferBytes) {
                return@withLock fail(
                    TransferError.TransferTooLarge(
                        maxBytes = config.maxTransferBytes,
                        actualBytes = prospectiveBufferedBytes,
                    ),
                )
            }

            fragments.add(
                EncryptedFragment(
                    ciphertext = transferData.ciphertext.copyOf(),
                    nonce = transferData.nonce.copyOf(),
                    fragmentIndex = fragmentIndex,
                    fragmentCount = fragmentCount,
                ),
            )

            if (fragments.size != fragmentCount) {
                bufferedCiphertextBytes = prospectiveBufferedBytes
                return@withLock ReceiveResult.ChunkAccepted(chunkIndex, false)
            }

            fragments.sortBy { it.fragmentIndex }
            for (expectedIndex in fragments.indices) {
                if (fragments[expectedIndex].fragmentIndex != expectedIndex) {
                    return@withLock fail(
                        TransferError.InvalidTransferMetadata("Missing or inconsistent fragment sequence for chunk $chunkIndex"),
                    )
                }
            }

            val totalSize = fragments.sumOf { it.ciphertext.size }
            if (totalSize > config.maxCiphertextSize) {
                return@withLock fail(TransferError.ChunkTooLarge(config.maxCiphertextSize, totalSize))
            }

            val fullCiphertext = ByteArray(totalSize)
            var offset = 0
            for (fragment in fragments) {
                fragment.ciphertext.copyInto(fullCiphertext, offset)
                offset += fragment.ciphertext.size
            }
            val fullNonce = fragments.first().nonce.copyOf()

            val aad = TransferProtocol.createDataAad(
                protocolVersion = transferData.protocolVersion,
                transferIdHash = transferData.transferIdHash,
                fileIdHash = transferData.fileIdHash,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
            )

            val plaintext = try {
                decryptor.decrypt(
                    ciphertext = fullCiphertext,
                    nonce = Nonce(fullNonce),
                    aad = aad,
                )
            } catch (e: CancellationException) {
                // The final fragment has not been committed to bufferedCiphertextBytes yet.
                // Remove it so a transport retry can safely re-submit and authenticate it.
                fragments.removeAll { it.fragmentIndex == fragmentIndex }
                fullCiphertext.fill(0)
                fullNonce.fill(0)
                throw e
            } catch (_: Exception) {
                fullCiphertext.fill(0)
                fullNonce.fill(0)
                return@withLock fail(TransferError.DecryptionFailed(chunkIndex))
            }

            // Authenticate immediately, but never retain plaintext in TransferState.
            val plaintextSize = plaintext.size
            plaintext.fill(0)

            // Fragment copies are no longer needed after authenticated reassembly.
            for (fragment in fragments) {
                fragment.ciphertext.fill(0)
                fragment.nonce.fill(0)
            }
            encryptedFragments[chunkIndex] = mutableListOf(
                EncryptedFragment(
                    ciphertext = fullCiphertext,
                    nonce = fullNonce,
                    fragmentIndex = 0,
                    fragmentCount = 1,
                ),
            )

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
        }

        private fun fail(error: TransferError): ReceiveResult.Error {
            state = TransferStateEnum.FAILED
            progressFlow.value = TransferReceiverProgress.Error(error)
            return ReceiveResult.Error(error)
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
                fragments.firstOrNull()
            }

        suspend fun isComplete(): Boolean =
            stateMutex.withLock { state == TransferStateEnum.COMPLETE }

        suspend fun isFailed(): Boolean =
            stateMutex.withLock { state == TransferStateEnum.FAILED }

        suspend fun getTotalBytes(): Long = stateMutex.withLock { bytesReceived }

        suspend fun clearEncryptedChunks() = stateMutex.withLock {
            for (i in encryptedFragments.indices) {
                for (fragment in encryptedFragments[i]) {
                    fragment.ciphertext.fill(0)
                    fragment.nonce.fill(0)
                }
                encryptedFragments[i].clear()
                receivedChunks[i] = false
            }
            receivedCount = 0
            bufferedCiphertextBytes = 0L
        }
    }

    private inner class TransferImportSourceImpl(
        private val state: TransferState,
        override val transferId: TransferId,
        override val fileId: FileId,
        private val totalBytes: Long,
    ) : TransferImportSource {
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
