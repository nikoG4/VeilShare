package dev.veilshare.core.transfer

import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.SignalingEnvelope
import dev.veilshare.core.model.TransferAccept
import dev.veilshare.core.model.TransferCancel
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferFailure
import dev.veilshare.core.model.TransferFailureCode
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.model.TransferOffer
import dev.veilshare.core.model.TransferReject
import dev.veilshare.core.vault.ImportProgress
import dev.veilshare.core.vault.VaultDirectoryId
import dev.veilshare.core.vault.VaultHandle
import dev.veilshare.core.vault.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class OutgoingTransferState {
    NEW,
    OFFERED,
    ACCEPTED,
    SENDING,
    COMPLETE,
    REJECTED,
    CANCELLED,
    FAILED,
}

enum class IncomingTransferState {
    OFFERED,
    ACCEPTED,
    RECEIVING,
    READY_TO_IMPORT,
    IMPORTING,
    COMPLETE,
    REJECTED,
    CANCELLED,
    FAILED,
}

sealed interface OutgoingControlResult {
    data object Accepted : OutgoingControlResult
    data class Rejected(val reason: String) : OutgoingControlResult
    data class RemoteCancel(val reason: String) : OutgoingControlResult
    data class RemoteFailure(val failure: TransferFailure) : OutgoingControlResult
    data class Ignored(val message: DecodedPeerMessage) : OutgoingControlResult
}

sealed interface IncomingDispatchResult {
    data class Data(val result: ReceiveResult) : IncomingDispatchResult
    data object ReadyToImport : IncomingDispatchResult
    data class RemoteCancel(val released: Boolean) : IncomingDispatchResult
    data class RemoteFailure(val released: Boolean, val failure: TransferFailure) : IncomingDispatchResult
    data class Control(val message: DecodedPeerMessage) : IncomingDispatchResult
}

/** Validates E2E OFFER metadata against the local bounded transfer configuration. */
fun validateTransferOffer(offer: TransferOffer, config: TransferConfig = TransferConfig()) {
    require(offer.sizeBytes <= config.maxTransferBytes) {
        "Offered file size ${offer.sizeBytes} exceeds max ${config.maxTransferBytes}"
    }
    require(offer.totalChunks <= config.maxChunks) {
        "Offered chunk count ${offer.totalChunks} exceeds max ${config.maxChunks}"
    }
    val expectedChunks = ((offer.sizeBytes - 1L) / config.chunkSize.toLong()) + 1L
    require(expectedChunks <= Int.MAX_VALUE.toLong()) { "Offered file requires too many chunks" }
    require(offer.totalChunks == expectedChunks.toInt()) {
        "OFFER totalChunks ${offer.totalChunks} does not match size/chunk configuration $expectedChunks"
    }
}

/**
 * Sender-side state machine for one encrypted single-file transfer.
 *
 * OFFER must be accepted before DATA can be sent. The TransferSource is closed on every
 * terminal pre-send path; once DATA begins, TransferSender owns exactly-once close.
 *
 * Remote CANCEL/FAILURE may arrive while DATA is being fragmented. The transport wrapper
 * checks terminal state before every DATA frame and COMPLETE so the next send boundary
 * aborts with CancellationException instead of continuing a peer-terminated transfer.
 */
class OutgoingSharingTransfer(
    private val session: EstablishedPeerSession,
    val transferId: TransferId,
    val fileId: FileId,
    private val source: TransferSource,
    private val crypto: EstablishedTransferCrypto,
    private val sender: TransferSender,
    private val config: TransferConfig = TransferConfig(),
) {
    private val gate = PeerSessionGate(session.sessionId, transferId, fileId)
    private val signalingSender = SignalingTransferNetworkSender(crypto.messenger)
    private val stateMutex = Mutex()
    private val sourceMutex = Mutex()
    private var stateValue = OutgoingTransferState.NEW
    private var sourceClosed = false

    private val networkSender = object : TransferNetworkSender {
        override suspend fun send(data: TransferData) {
            requireSendingState()
            signalingSender.send(data)
        }

        override suspend fun complete(
            transferIdHash: String,
            fileIdHash: String,
            totalChunks: Int,
        ) {
            requireSendingState()
            signalingSender.complete(transferIdHash, fileIdHash, totalChunks)
        }

        override suspend fun cancel(transferIdHash: String, reason: String) {
            signalingSender.cancel(transferIdHash, reason)
        }
    }

    val state: OutgoingTransferState get() = stateValue

    val offer: TransferOffer by lazy {
        require(source.fileSize > 0) { "Empty files are not supported by Sharing V1" }
        require(source.fileSize <= config.maxTransferBytes) {
            "File size ${source.fileSize} exceeds max ${config.maxTransferBytes}"
        }
        val chunksLong = ((source.fileSize - 1L) / config.chunkSize.toLong()) + 1L
        require(chunksLong <= config.maxChunks.toLong()) { "File requires too many chunks" }
        TransferOffer(
            fileId = fileId,
            displayName = source.displayName,
            mimeHint = source.mimeHint,
            sizeBytes = source.fileSize,
            totalChunks = chunksLong.toInt(),
        )
    }

    suspend fun sendOffer(): TransferOffer {
        stateMutex.withLock {
            check(stateValue == OutgoingTransferState.NEW) { "OFFER can only be sent once" }
        }
        val value = offer
        validateTransferOffer(value, config)
        crypto.messenger.send(DecodedPeerMessage.Offer(value))
        stateMutex.withLock {
            check(stateValue == OutgoingTransferState.NEW) { "Transfer changed state while OFFER was sending" }
            stateValue = OutgoingTransferState.OFFERED
        }
        return value
    }

    suspend fun handleControl(envelope: SignalingEnvelope): OutgoingControlResult {
        val peerEnvelope = crypto.inbox.decodeRelay(envelope)
        return when (val message = gate.decode(peerEnvelope)) {
            is DecodedPeerMessage.Accept -> {
                stateMutex.withLock {
                    check(stateValue == OutgoingTransferState.OFFERED) { "Unexpected ACCEPT in state $stateValue" }
                    stateValue = OutgoingTransferState.ACCEPTED
                }
                OutgoingControlResult.Accepted
            }
            is DecodedPeerMessage.Reject -> {
                stateMutex.withLock {
                    check(stateValue == OutgoingTransferState.OFFERED) { "Unexpected REJECT in state $stateValue" }
                    stateValue = OutgoingTransferState.REJECTED
                }
                closeSourceBestEffort()
                OutgoingControlResult.Rejected(message.value.reason)
            }
            is DecodedPeerMessage.Cancel -> {
                val wasSending = stateMutex.withLock {
                    val sending = stateValue == OutgoingTransferState.SENDING
                    if (stateValue != OutgoingTransferState.COMPLETE) {
                        stateValue = OutgoingTransferState.CANCELLED
                    }
                    sending
                }
                if (!wasSending) closeSourceBestEffort()
                OutgoingControlResult.RemoteCancel(message.value.reason)
            }
            is DecodedPeerMessage.Failure -> {
                val wasSending = stateMutex.withLock {
                    val sending = stateValue == OutgoingTransferState.SENDING
                    if (stateValue != OutgoingTransferState.COMPLETE) {
                        stateValue = OutgoingTransferState.FAILED
                    }
                    sending
                }
                if (!wasSending) closeSourceBestEffort()
                OutgoingControlResult.RemoteFailure(message.value)
            }
            else -> OutgoingControlResult.Ignored(message)
        }
    }

    suspend fun sendAccepted(): TransferResult {
        stateMutex.withLock {
            check(stateValue == OutgoingTransferState.ACCEPTED) { "Transfer must be accepted before DATA" }
            stateValue = OutgoingTransferState.SENDING
        }

        return try {
            val result = sender.send(transferId, fileId, source, crypto.encryptor, networkSender)
            val finalState = stateMutex.withLock {
                if (stateValue == OutgoingTransferState.SENDING) {
                    stateValue = OutgoingTransferState.COMPLETE
                }
                stateValue
            }
            if (finalState != OutgoingTransferState.COMPLETE) {
                throw CancellationException("Peer terminated transfer while sender was completing: $finalState")
            }
            result
        } catch (cancelled: CancellationException) {
            val notifyPeer = stateMutex.withLock {
                val alreadyRemoteTerminal =
                    stateValue == OutgoingTransferState.CANCELLED || stateValue == OutgoingTransferState.FAILED
                if (!alreadyRemoteTerminal) stateValue = OutgoingTransferState.CANCELLED
                !alreadyRemoteTerminal
            }
            if (notifyPeer) {
                withContext(NonCancellable) {
                    runCatching { signalingSender.cancel(transferIdHash(), "local cancellation") }
                }
            }
            throw cancelled
        } catch (failure: Throwable) {
            val notifyPeer = stateMutex.withLock {
                val alreadyRemoteTerminal =
                    stateValue == OutgoingTransferState.CANCELLED || stateValue == OutgoingTransferState.FAILED
                if (!alreadyRemoteTerminal) stateValue = OutgoingTransferState.FAILED
                !alreadyRemoteTerminal
            }
            if (notifyPeer) {
                withContext(NonCancellable) {
                    runCatching {
                        crypto.messenger.send(
                            DecodedPeerMessage.Failure(
                                TransferFailure(
                                    transferIdHash = transferIdHash(),
                                    code = failure.toFailureCode(),
                                    details = "sender transfer failed",
                                ),
                            ),
                        )
                    }
                }
            }
            throw failure
        } finally {
            // TransferSender owns source.close() once DATA begins.
            sourceMutex.withLock { sourceClosed = true }
        }
    }

    suspend fun cancel(reason: String) {
        val previous = stateMutex.withLock {
            if (stateValue == OutgoingTransferState.COMPLETE || stateValue == OutgoingTransferState.CANCELLED) {
                return
            }
            val value = stateValue
            stateValue = OutgoingTransferState.CANCELLED
            value
        }
        if (previous != OutgoingTransferState.SENDING) closeSourceBestEffort()
        signalingSender.cancel(transferIdHash(), reason)
    }

    suspend fun abandonBeforeSend() {
        stateMutex.withLock {
            check(stateValue != OutgoingTransferState.SENDING && stateValue != OutgoingTransferState.COMPLETE) {
                "Cannot abandon a transfer after DATA started"
            }
            if (
                stateValue == OutgoingTransferState.NEW ||
                stateValue == OutgoingTransferState.OFFERED ||
                stateValue == OutgoingTransferState.ACCEPTED
            ) {
                stateValue = OutgoingTransferState.CANCELLED
            }
        }
        closeSourceBestEffort()
    }

    fun closeCrypto() {
        crypto.close()
    }

    private suspend fun requireSendingState() {
        val current = stateMutex.withLock { stateValue }
        if (current != OutgoingTransferState.SENDING) {
            throw CancellationException("Transfer is no longer in SENDING state: $current")
        }
    }

    private suspend fun closeSourceBestEffort() {
        val shouldClose = sourceMutex.withLock {
            if (sourceClosed) false else {
                sourceClosed = true
                true
            }
        }
        if (!shouldClose) return
        withContext(NonCancellable) {
            runCatching { source.close() }
        }
    }

    private fun transferIdHash(): String = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
}

data class IncomingTransferOffer(
    val transferId: TransferId,
    val offer: TransferOffer,
)

/** Decodes and validates an encrypted OFFER before allocating receiver transfer state. */
suspend fun decodeIncomingTransferOffer(
    envelope: SignalingEnvelope,
    session: EstablishedPeerSession,
    crypto: EstablishedTransferCrypto,
    config: TransferConfig = TransferConfig(),
): IncomingTransferOffer {
    val peerEnvelope = crypto.inbox.decodeRelay(envelope)
    require(peerEnvelope.sessionId == session.sessionId) { "OFFER belongs to a different session" }
    val message = PeerMessageCodec().decode(peerEnvelope)
    val offer = (message as? DecodedPeerMessage.Offer)?.value
        ?: throw IllegalArgumentException("Expected OFFER")
    validateTransferOffer(offer, config)
    return IncomingTransferOffer(peerEnvelope.transferId, offer)
}

/**
 * Receiver-side state machine bound to one validated OFFER.
 *
 * Vault import is impossible until all authenticated DATA chunks completed locally and a
 * coherent remote COMPLETE message was received. State is checked before DATA touches the
 * receiver so a peer cannot pre-buffer file content before local ACCEPT. dispatch()/accept()
 * are intended to be serialized by the owning session event loop.
 */
class IncomingSharingTransfer(
    private val session: EstablishedPeerSession,
    val transferId: TransferId,
    val offer: TransferOffer,
    private val crypto: EstablishedTransferCrypto,
    private val receiver: TransferReceiver,
    private val config: TransferConfig = TransferConfig(),
) {
    private val gate = PeerSessionGate(session.sessionId, transferId, offer.fileId)
    private val importer = ReceivedTransferVaultImporter(receiver)
    private var stateValue = IncomingTransferState.OFFERED
    private var localDataComplete = false

    init {
        validateTransferOffer(offer, config)
    }

    val state: IncomingTransferState get() = stateValue

    suspend fun accept() {
        check(stateValue == IncomingTransferState.OFFERED) { "OFFER is no longer pending" }
        crypto.messenger.send(DecodedPeerMessage.Accept(TransferAccept(offer.fileId)))
        stateValue = IncomingTransferState.ACCEPTED
    }

    suspend fun reject(reason: String) {
        check(stateValue == IncomingTransferState.OFFERED) { "OFFER is no longer pending" }
        crypto.messenger.send(DecodedPeerMessage.Reject(TransferReject(offer.fileId, reason)))
        stateValue = IncomingTransferState.REJECTED
    }

    suspend fun dispatch(envelope: SignalingEnvelope): IncomingDispatchResult {
        check(stateValue != IncomingTransferState.COMPLETE) { "Transfer is already complete" }
        val peerEnvelope = crypto.inbox.decodeRelay(envelope)
        return when (val message = gate.decode(peerEnvelope)) {
            is DecodedPeerMessage.Data -> {
                check(
                    stateValue == IncomingTransferState.ACCEPTED ||
                        stateValue == IncomingTransferState.RECEIVING,
                ) { "DATA received before ACCEPT" }
                stateValue = IncomingTransferState.RECEIVING
                val receive = receiver.receive(message.value)
                when (receive) {
                    is ReceiveResult.TransferComplete -> localDataComplete = true
                    is ReceiveResult.Error -> {
                        stateValue = IncomingTransferState.FAILED
                        sendReceiverFailure(receive.error)
                    }
                    else -> Unit
                }
                IncomingDispatchResult.Data(receive)
            }
            is DecodedPeerMessage.Complete -> {
                check(stateValue == IncomingTransferState.RECEIVING || stateValue == IncomingTransferState.ACCEPTED) {
                    "Unexpected COMPLETE in state $stateValue"
                }
                require(message.value.totalChunks == offer.totalChunks) {
                    "COMPLETE totalChunks does not match OFFER"
                }
                require(localDataComplete) { "Remote COMPLETE arrived before authenticated DATA completion" }
                stateValue = IncomingTransferState.READY_TO_IMPORT
                IncomingDispatchResult.ReadyToImport
            }
            is DecodedPeerMessage.Cancel -> {
                stateValue = IncomingTransferState.CANCELLED
                val released = receiver.abort(message.value.transferIdHash, message.value.reason)
                IncomingDispatchResult.RemoteCancel(released)
            }
            is DecodedPeerMessage.Failure -> {
                stateValue = IncomingTransferState.FAILED
                val released = receiver.abort(
                    message.value.transferIdHash,
                    "peer failure: ${message.value.code}",
                )
                IncomingDispatchResult.RemoteFailure(released, message.value)
            }
            else -> IncomingDispatchResult.Control(message)
        }
    }

    suspend fun importIntoVault(
        vault: VaultHandle,
        parent: VaultDirectoryId? = null,
        progress: suspend (ImportProgress) -> Unit = {},
    ): VaultItem.File {
        check(stateValue == IncomingTransferState.READY_TO_IMPORT) { "Transfer is not ready for vault import" }
        stateValue = IncomingTransferState.IMPORTING
        return try {
            importer.importCompleted(
                transferId = transferId,
                offer = offer,
                vault = vault,
                parent = parent,
                progress = progress,
            ).also {
                stateValue = IncomingTransferState.COMPLETE
            }
        } catch (cancelled: CancellationException) {
            stateValue = IncomingTransferState.CANCELLED
            throw cancelled
        } catch (failure: Throwable) {
            stateValue = IncomingTransferState.FAILED
            throw failure
        }
    }

    suspend fun cancel(reason: String) {
        if (stateValue == IncomingTransferState.COMPLETE || stateValue == IncomingTransferState.CANCELLED) return
        val hash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
        receiver.abort(hash, reason)
        crypto.messenger.send(DecodedPeerMessage.Cancel(TransferCancel(hash, reason)))
        stateValue = IncomingTransferState.CANCELLED
    }

    fun closeCrypto() {
        crypto.close()
    }

    private suspend fun sendReceiverFailure(error: TransferError) {
        runCatching {
            crypto.messenger.send(
                DecodedPeerMessage.Failure(
                    TransferFailure(
                        transferIdHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray()),
                        code = error.toFailureCode(),
                        details = "receiver transfer failed",
                    ),
                ),
            )
        }
    }
}

private fun Throwable.toFailureCode(): TransferFailureCode = when (this) {
    is TransferException -> error.toFailureCode()
    is SecurityException -> TransferFailureCode.AUTHENTICATION_FAILED
    else -> TransferFailureCode.INTERNAL_ERROR
}

private fun TransferError.toFailureCode(): TransferFailureCode = when (this) {
    is TransferError.DecryptionFailed -> TransferFailureCode.DECRYPTION_FAILED
    is TransferError.TooManyActiveTransfers,
    is TransferError.TransferTooLarge,
    is TransferError.ChunkTooLarge -> TransferFailureCode.LIMIT_EXCEEDED
    is TransferError.TransferExpired -> TransferFailureCode.TRANSFER_EXPIRED
    is TransferError.TransferCancelled -> TransferFailureCode.CANCELLED
    is TransferError.IoError -> TransferFailureCode.IO_ERROR
    is TransferError.InvalidChunkIndex,
    is TransferError.InvalidTransferMetadata,
    is TransferError.DuplicateChunk -> TransferFailureCode.PROTOCOL_ERROR
}
