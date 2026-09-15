package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.PeerEnvelope
import dev.veilshare.core.model.SessionId
import dev.veilshare.core.model.TransferCancel
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PeerSessionGateTest {
    private val sessionId = SessionId("session-bound")
    private val transferId = TransferId("transfer-bound")
    private val fileId = FileId("file-bound")
    private val codec = PeerMessageCodec()

    @Test
    fun `wrong envelope session or transfer is rejected before receiver`() = runTest {
        val receiver = RecordingReceiver()
        val dispatcher = dispatcher(receiver)
        val valid = dataEnvelope()

        assertFailsWith<IllegalArgumentException> {
            dispatcher.dispatch(valid.copy(sessionId = SessionId("other-session")))
        }
        assertFailsWith<IllegalArgumentException> {
            dispatcher.dispatch(valid.copy(transferId = TransferId("other-transfer")))
        }
        assertEquals(0, receiver.receiveCalls)
    }

    @Test
    fun `inner transfer and file hashes are independently bound`() = runTest {
        val receiver = RecordingReceiver()
        val dispatcher = dispatcher(receiver)
        val validData = validData()

        val wrongTransfer = codec.encode(
            sessionId,
            transferId,
            DecodedPeerMessage.Data(validData.copy(transferIdHash = "wrong")),
        )
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(wrongTransfer) }

        val wrongFile = codec.encode(
            sessionId,
            transferId,
            DecodedPeerMessage.Data(validData.copy(fileIdHash = "wrong")),
        )
        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(wrongFile) }
        assertEquals(0, receiver.receiveCalls)
    }

    @Test
    fun `valid data reaches receiver exactly once`() = runTest {
        val receiver = RecordingReceiver()
        val result = dispatcher(receiver).dispatch(dataEnvelope())

        val dataResult = assertIs<ReceiverDispatchResult.Data>(result)
        assertIs<ReceiveResult.ChunkAccepted>(dataResult.result)
        assertEquals(1, receiver.receiveCalls)
    }

    @Test
    fun `valid remote cancel is bound and releases receiver state`() = runTest {
        val receiver = RecordingReceiver()
        receiver.abortResult = true
        val expectedTransferHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
        val envelope = codec.encode(
            sessionId,
            transferId,
            DecodedPeerMessage.Cancel(
                TransferCancel(expectedTransferHash, "sender cancelled"),
            ),
        )

        val result = dispatcher(receiver).dispatch(envelope)
        val cancel = assertIs<ReceiverDispatchResult.RemoteCancel>(result)
        assertEquals(true, cancel.released)
        assertEquals(expectedTransferHash, receiver.lastAbortHash)
        assertEquals("sender cancelled", receiver.lastAbortReason)
    }

    private fun dispatcher(receiver: TransferReceiver) = ReceiverPeerDispatcher(
        receiver = receiver,
        gate = PeerSessionGate(sessionId, transferId, fileId, codec),
    )

    private fun dataEnvelope(): PeerEnvelope = codec.encode(
        sessionId,
        transferId,
        DecodedPeerMessage.Data(validData()),
    )

    private fun validData() = TransferData(
        transferIdHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray()),
        fileIdHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray()),
        chunkIndex = 0,
        totalChunks = 1,
        ciphertext = byteArrayOf(1, 2, 3),
        nonce = ByteArray(12) { 4 },
    )

    private class RecordingReceiver : TransferReceiver {
        var receiveCalls = 0
        var abortResult = false
        var lastAbortHash: String? = null
        var lastAbortReason: String? = null
        private val progress = MutableStateFlow<TransferReceiverProgress?>(null)

        override suspend fun receive(transferData: TransferData): ReceiveResult {
            receiveCalls++
            return ReceiveResult.ChunkAccepted(transferData.chunkIndex, transferData.chunkIndex == transferData.totalChunks - 1)
        }

        override suspend fun getImportSource(transferId: TransferId, fileId: FileId): TransferImportSource =
            error("not needed")

        override fun getProgress(transferId: TransferId): StateFlow<TransferReceiverProgress?> = progress

        override suspend fun abort(transferIdHash: String, reason: String): Boolean {
            lastAbortHash = transferIdHash
            lastAbortReason = reason
            return abortResult
        }

        override suspend fun sweepExpired(): Int = 0
    }
}
