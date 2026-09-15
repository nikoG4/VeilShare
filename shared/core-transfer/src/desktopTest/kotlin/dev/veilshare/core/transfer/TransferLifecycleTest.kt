package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferLifecycleTest {
    @Test
    fun `idle transfers are swept before active transfer limit is enforced`() = runTest {
        val clock = MutableTransferClock()
        val receiver = InMemoryTransferReceiver(
            decryptor = PassthroughDecryptor,
            config = TransferConfig(
                maxActiveTransfers = 2,
                idleTimeoutMs = 100,
                maxLifetimeMs = 1_000,
            ),
            clock = clock,
        )

        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("transfer-a", chunkIndex = 0, totalChunks = 2)))
        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("transfer-b", chunkIndex = 0, totalChunks = 2)))
        val blocked = receiver.receive(fragment("transfer-c", chunkIndex = 0, totalChunks = 2))
        assertIs<ReceiveResult.Error>(blocked)
        assertIs<TransferError.TooManyActiveTransfers>(blocked.error)

        clock.advance(101)
        // receive() performs an opportunistic sweep before checking capacity.
        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("transfer-c", chunkIndex = 0, totalChunks = 2)))
    }

    @Test
    fun `absolute lifetime expires a transfer even when recent activity is within idle timeout`() = runTest {
        val clock = MutableTransferClock()
        val receiver = InMemoryTransferReceiver(
            decryptor = PassthroughDecryptor,
            config = TransferConfig(
                idleTimeoutMs = 200,
                maxLifetimeMs = 250,
            ),
            clock = clock,
        )

        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("long-lived", chunkIndex = 0, totalChunks = 4)))
        clock.advance(90)
        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("long-lived", chunkIndex = 1, totalChunks = 4)))
        clock.advance(90)
        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("long-lived", chunkIndex = 2, totalChunks = 4)))

        // Only 70 ms idle, but 250 ms total lifetime.
        clock.advance(70)
        assertEquals(1, receiver.sweepExpired())
    }

    @Test
    fun `explicit abort releases capacity and is idempotent`() = runTest {
        val receiver = InMemoryTransferReceiver(
            decryptor = PassthroughDecryptor,
            config = TransferConfig(maxActiveTransfers = 1),
        )

        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("abort-me", chunkIndex = 0, totalChunks = 2)))
        val blocked = receiver.receive(fragment("next", chunkIndex = 0, totalChunks = 2))
        assertIs<ReceiveResult.Error>(blocked)
        assertTrue(receiver.abort("abort-me", "remote cancel"))
        assertEquals(false, receiver.abort("abort-me", "remote cancel again"))
        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(fragment("next", chunkIndex = 0, totalChunks = 2)))
    }

    @Test
    fun `completed transfer has exactly one importing consumer`() = runTest {
        val transferId = TransferId("single-consumer-transfer")
        val fileId = FileId("single-consumer-file")
        val transferHash = TransferPlatform.sha256ToHex(transferId.value.encodeToByteArray())
        val fileHash = TransferPlatform.sha256ToHex(fileId.value.encodeToByteArray())
        val receiver = InMemoryTransferReceiver(PassthroughDecryptor)

        val result = receiver.receive(
            TransferData(
                transferIdHash = transferHash,
                fileIdHash = fileHash,
                chunkIndex = 0,
                totalChunks = 1,
                ciphertext = "payload".encodeToByteArray(),
                nonce = ByteArray(12),
            ),
        )
        assertIs<ReceiveResult.TransferComplete>(result)

        val first = receiver.getImportSource(transferId, fileId)
        val second = receiver.getImportSource(transferId, fileId)
        val firstHandle = first.openRead()
        assertFailsWith<IllegalStateException> { second.openRead() }

        // IMPORTING is owned by the vault/read handle and must not be timed out underneath it.
        assertEquals(0, receiver.sweepExpired())
        firstHandle.close()
        assertFailsWith<IllegalStateException> { receiver.getImportSource(transferId, fileId) }
    }

    private fun fragment(
        transferIdHash: String,
        chunkIndex: Int,
        totalChunks: Int,
    ): TransferData = TransferData(
        transferIdHash = transferIdHash,
        fileIdHash = "file-$transferIdHash",
        chunkIndex = chunkIndex,
        totalChunks = totalChunks,
        ciphertext = byteArrayOf((chunkIndex + 1).toByte()),
        nonce = ByteArray(12) { 7 },
    )

    private class MutableTransferClock(
        private var now: Long = 0,
    ) : TransferClock {
        override fun nowMillis(): Long = now
        fun advance(delta: Long) {
            require(delta >= 0)
            now += delta
        }
    }

    private object PassthroughDecryptor : TransferDecryptor {
        override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray = ciphertext.copyOf()
        override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce, aad: ByteArray): ByteArray = ciphertext.copyOf()
    }
}
