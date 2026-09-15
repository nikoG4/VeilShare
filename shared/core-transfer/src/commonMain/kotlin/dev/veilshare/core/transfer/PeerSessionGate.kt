package dev.veilshare.core.transfer

import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.TransferId

/**
 * Validates envelope routing identity and payload identifiers before a decoded peer
 * message is allowed to reach a session/transfer handler.
 */
class PeerSessionGate(
    private val expectedSessionId: SessionId,
    private val expectedTransferId: TransferId,
    private val expectedFileId: FileId? = null,
    private val codec: PeerMessageCodec = PeerMessageCodec(),
) {
    private val expectedTransferIdHash =
        TransferPlatform.sha256ToHex(expectedTransferId.value.encodeToByteArray())
    private val expectedFileIdHash = expectedFileId?.let {
        TransferPlatform.sha256ToHex(it.value.encodeToByteArray())
    }

    fun decode(envelope: PeerEnvelope): DecodedPeerMessage {
        require(envelope.sessionId == expectedSessionId) { "Peer envelope belongs to a different session" }
        require(envelope.transferId == expectedTransferId) { "Peer envelope belongs to a different transfer" }

        val message = codec.decode(envelope)
        when (message) {
            is DecodedPeerMessage.Offer -> {
                if (expectedFileId != null) {
                    require(message.value.fileId == expectedFileId) { "OFFER fileId mismatch" }
                }
            }
            is DecodedPeerMessage.Accept -> {
                if (expectedFileId != null) {
                    require(message.value.fileId == expectedFileId) { "ACCEPT fileId mismatch" }
                }
            }
            is DecodedPeerMessage.Reject -> {
                if (expectedFileId != null) {
                    require(message.value.fileId == expectedFileId) { "REJECT fileId mismatch" }
                }
            }
            is DecodedPeerMessage.Data -> {
                require(message.value.transferIdHash == expectedTransferIdHash) { "DATA transferIdHash mismatch" }
                expectedFileIdHash?.let { expected ->
                    require(message.value.fileIdHash == expected) { "DATA fileIdHash mismatch" }
                }
            }
            is DecodedPeerMessage.Complete -> {
                require(message.value.transferIdHash == expectedTransferIdHash) { "COMPLETE transferIdHash mismatch" }
                expectedFileIdHash?.let { expected ->
                    require(message.value.fileIdHash == expected) { "COMPLETE fileIdHash mismatch" }
                }
            }
            is DecodedPeerMessage.Cancel ->
                require(message.value.transferIdHash == expectedTransferIdHash) { "CANCEL transferIdHash mismatch" }
            is DecodedPeerMessage.Failure ->
                require(message.value.transferIdHash == expectedTransferIdHash) { "FAILURE transferIdHash mismatch" }
            is DecodedPeerMessage.Hello,
            is DecodedPeerMessage.Confirm,
            is DecodedPeerMessage.ConfirmAck -> Unit
        }
        return message
    }
}

sealed interface ReceiverDispatchResult {
    data class Data(val result: ReceiveResult) : ReceiverDispatchResult
    data class RemoteCancel(val released: Boolean) : ReceiverDispatchResult
    data class RemoteFailure(val released: Boolean, val failure: dev.veilshare.core.model.TransferFailure) : ReceiverDispatchResult
    data class Control(val message: DecodedPeerMessage) : ReceiverDispatchResult
}

/**
 * Receiver-side adapter for an already bound session/transfer/file.
 *
 * DATA goes to the hardened receiver. CANCEL/FAILURE release buffered transfer state.
 * Other control messages are returned to the higher-level session state machine.
 */
class ReceiverPeerDispatcher(
    private val receiver: TransferReceiver,
    private val gate: PeerSessionGate,
) {
    suspend fun dispatch(envelope: PeerEnvelope): ReceiverDispatchResult {
        return when (val message = gate.decode(envelope)) {
            is DecodedPeerMessage.Data -> ReceiverDispatchResult.Data(receiver.receive(message.value))
            is DecodedPeerMessage.Cancel -> ReceiverDispatchResult.RemoteCancel(
                receiver.abort(message.value.transferIdHash, message.value.reason),
            )
            is DecodedPeerMessage.Failure -> ReceiverDispatchResult.RemoteFailure(
                released = receiver.abort(
                    message.value.transferIdHash,
                    "peer failure: ${message.value.code}",
                ),
                failure = message.value,
            )
            else -> ReceiverDispatchResult.Control(message)
        }
    }
}
