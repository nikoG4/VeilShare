package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.SharingProtocol
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TransferSecurityHardeningTest {
    private val random = object : SecureRandom {
        override fun bytes(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }
    }

    private val identityCipher = object : AuthenticatedCipher {
        override suspend fun seal(
            key: SensitiveBytes,
            plaintext: ByteArray,
            aad: ByteArray,
        ): SealedBytes = SealedBytes(Nonce(ByteArray(12)), plaintext.copyOf())

        override suspend fun sealWithNonce(
            key: SensitiveBytes,
            nonce: Nonce,
            plaintext: ByteArray,
            aad: ByteArray,
        ): SealedBytes = SealedBytes(nonce, plaintext.copyOf())

        override suspend fun open(
            key: SensitiveBytes,
            sealed: SealedBytes,
            aad: ByteArray,
        ): ByteArray = sealed.ciphertext.copyOf()
    }

    private val key = SensitiveBytes(ByteArray(32) { it.toByte() })

    @Test
    fun dataAadBindsMutableChunkMetadata() {
        val base = TransferProtocol.createDataAad(
            SharingProtocol.VERSION,
            transferIdHash = "transfer-a",
            fileIdHash = "file-a",
            chunkIndex = 0,
            totalChunks = 2,
        )
        val otherTransfer = TransferProtocol.createDataAad(
            SharingProtocol.VERSION,
            transferIdHash = "transfer-b",
            fileIdHash = "file-a",
            chunkIndex = 0,
            totalChunks = 2,
        )
        val otherFile = TransferProtocol.createDataAad(
            SharingProtocol.VERSION,
            transferIdHash = "transfer-a",
            fileIdHash = "file-b",
            chunkIndex = 0,
            totalChunks = 2,
        )
        val otherIndex = TransferProtocol.createDataAad(
            SharingProtocol.VERSION,
            transferIdHash = "transfer-a",
            fileIdHash = "file-a",
            chunkIndex = 1,
            totalChunks = 2,
        )
        val otherCount = TransferProtocol.createDataAad(
            SharingProtocol.VERSION,
            transferIdHash = "transfer-a",
            fileIdHash = "file-a",
            chunkIndex = 0,
            totalChunks = 3,
        )

        assertNotEquals(base.contentToString(), otherTransfer.contentToString())
        assertNotEquals(base.contentToString(), otherFile.contentToString())
        assertNotEquals(base.contentToString(), otherIndex.contentToString())
        assertNotEquals(base.contentToString(), otherCount.contentToString())
    }

    @Test
    fun receiverRejectsMetadataMutationWithinTransfer() = runTest {
        val receiver = InMemoryTransferReceiver(DefaultTransferDecryptor(identityCipher, key))
        val transferId = TransferId("metadata-binding-transfer")
        val fileA = FileId("file-a")
        val fileB = FileId("file-b")
        val transferHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileAHash = Hash.sha256(fileA.value.encodeToByteArray()).toHex()
        val fileBHash = Hash.sha256(fileB.value.encodeToByteArray()).toHex()

        val first = TransferData(
            transferIdHash = transferHash,
            fileIdHash = fileAHash,
            chunkIndex = 0,
            totalChunks = 2,
            ciphertext = byteArrayOf(1),
            nonce = ByteArray(12),
        )
        assertIs<ReceiveResult.ChunkAccepted>(receiver.receive(first))

        val mutated = TransferData(
            transferIdHash = transferHash,
            fileIdHash = fileBHash,
            chunkIndex = 1,
            totalChunks = 2,
            ciphertext = byteArrayOf(2),
            nonce = ByteArray(12),
        )
        val result = receiver.receive(mutated)
        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.InvalidTransferMetadata>(result.error)
    }

    @Test
    fun receiverRejectsChunkCountAboveConfiguredLimit() = runTest {
        val config = TransferConfig(maxChunks = 4)
        val receiver = InMemoryTransferReceiver(DefaultTransferDecryptor(identityCipher, key), config)

        val result = receiver.receive(
            TransferData(
                transferIdHash = "transfer",
                fileIdHash = "file",
                chunkIndex = 0,
                totalChunks = 5,
                ciphertext = byteArrayOf(1),
                nonce = ByteArray(12),
            ),
        )

        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.InvalidTransferMetadata>(result.error)
    }

    @Test
    fun importReadNeverReturnsMoreThanMaxBytes() = runTest {
        val receiver = InMemoryTransferReceiver(DefaultTransferDecryptor(identityCipher, key))
        val transferId = TransferId("bounded-read-transfer")
        val fileId = FileId("bounded-read-file")
        val transferHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()
        val bytes = ByteArray(10) { it.toByte() }

        val result = receiver.receive(
            TransferData(
                transferIdHash = transferHash,
                fileIdHash = fileHash,
                chunkIndex = 0,
                totalChunks = 1,
                ciphertext = bytes,
                nonce = ByteArray(12),
            ),
        )
        assertIs<ReceiveResult.TransferComplete>(result)

        val handle = receiver.getImportSource(transferId, fileId).openRead()
        val first = handle.read(4)
        val second = handle.read(4)
        val third = handle.read(4)
        val eof = handle.read(4)

        assertEquals(4, first.size)
        assertEquals(4, second.size)
        assertEquals(2, third.size)
        assertEquals(0, eof.size)
        assertContentEquals(bytes, first + second + third)
        handle.close()
    }

    @Test
    fun cancellationIsNeverRetried() = runTest {
        var attempts = 0
        val sender = DefaultTransferSender(
            random,
            TransferConfig(
                chunkSize = 16,
                maxRetries = 3,
                baseRetryDelayMs = 0,
                maxRetryDelayMs = 0,
            ),
        )
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attempts++
                throw CancellationException("cancel")
            }

            override suspend fun complete(
                transferIdHash: String,
                fileIdHash: String,
                totalChunks: Int,
            ) = Unit

            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<CancellationException> {
            sender.send(
                transferId = TransferId("cancel-transfer"),
                fileId = FileId("cancel-file"),
                source = ByteArrayTransferSource(byteArrayOf(1, 2, 3)),
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }
        assertEquals(1, attempts)
    }
}
