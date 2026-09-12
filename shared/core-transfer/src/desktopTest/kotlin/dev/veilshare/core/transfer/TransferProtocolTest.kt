package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransferProtocolTest {
    private val random = object : SecureRandom { override fun bytes(size: Int) = ByteArray(size) }
    private val cipher = object : AuthenticatedCipher {
        override suspend fun seal(key: SensitiveBytes, plaintext: ByteArray, aad: ByteArray): dev.veilshare.core.crypto.SealedBytes {
            val nonce = Nonce(random.bytes(12))
            return dev.veilshare.core.crypto.SealedBytes(nonce, plaintext.copyOf())
        }
        override suspend fun sealWithNonce(key: SensitiveBytes, nonce: Nonce, plaintext: ByteArray, aad: ByteArray): dev.veilshare.core.crypto.SealedBytes {
            return dev.veilshare.core.crypto.SealedBytes(nonce, plaintext.copyOf())
        }
        override suspend fun open(key: SensitiveBytes, sealed: dev.veilshare.core.crypto.SealedBytes, aad: ByteArray): ByteArray {
            return sealed.ciphertext
        }
    }
    private val key = SensitiveBytes(ByteArray(32) { it.toByte() })

    @Test fun chunkSizeCalculation() = runTest {
        val config = TransferConfig(chunkSize = 100)
        val sender = DefaultTransferSender(random, config)
        
        assertEquals(1, sender.calculateTotalChunks(50))
        assertEquals(1, sender.calculateTotalChunks(100))
        assertEquals(3, sender.calculateTotalChunks(250))
        assertEquals(5, sender.calculateTotalChunks(401))
    }

    @Test fun encryptDecryptRoundTrip() = runTest {
        val encryptor = DefaultTransferEncryptor(cipher, key)
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val nonce = Nonce(ByteArray(12) { it.toByte() })
        val plaintext = "test data".encodeToByteArray()
        
        val ciphertext = encryptor.encrypt(plaintext, nonce)
        val decrypted = decryptor.decrypt(ciphertext, nonce)
        
        assertEquals(plaintext.contentToString(), decrypted.contentToString())
    }

    @Test fun byteArrayTransferSource() = runTest {
        val data = ByteArray(1000) { (it % 256).toByte() }
        val source = ByteArrayTransferSource(data)
        
        assertEquals(data.size.toLong(), source.fileSize)
        
        val chunk1 = source.readChunk(0, 100)
        assertEquals(100, chunk1.size)
        assertEquals(data.sliceArray(0..99).contentToString(), chunk1.contentToString())
        
        val chunk2 = source.readChunk(100, 200)
        assertEquals(200, chunk2.size)
        assertEquals(data.sliceArray(100..299).contentToString(), chunk2.contentToString())
        
        val lastChunk = source.readChunk(950, 100)
        assertEquals(50, lastChunk.size)
        
        val emptyChunk = source.readChunk(1000, 100)
        assertEquals(0, emptyChunk.size)
    }

    @Test fun receiverAcceptsChunksInOrder() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-transfer")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()
        
        // Send chunk 0
        val chunk0 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 1 },
            nonce = ByteArray(12) { 0 },
        )
        val result0 = receiver.receive(chunk0)
        assertTrue(result0 is dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted)
        assertEquals(0, (result0 as dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted).chunkIndex)
        assertEquals(false, (result0 as dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted).isFinal)
        
        // Send chunk 1
        val chunk1 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 1,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 2 },
            nonce = ByteArray(12) { 0 },
        )
        val result1 = receiver.receive(chunk1)
        assertTrue(result1 is dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted)
        
        // Send final chunk 2
        val chunk2 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 2,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 3 },
            nonce = ByteArray(12) { 0 },
        )
        val result2 = receiver.receive(chunk2)
        assertTrue(result2 is dev.veilshare.core.transfer.ReceiveResult.TransferComplete)
        assertEquals(3, (result2 as dev.veilshare.core.transfer.ReceiveResult.TransferComplete).totalChunks)
    }

    @Test fun receiverRejectsDuplicateChunks() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-transfer")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()
        
        val chunk = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 2,
            ciphertext = ByteArray(10) { 1 },
            nonce = ByteArray(12) { 0 },
        )
        
        receiver.receive(chunk)
        val duplicateResult = receiver.receive(chunk)
        
        assertTrue(duplicateResult is dev.veilshare.core.transfer.ReceiveResult.DuplicateChunk)
        assertEquals(0, (duplicateResult as dev.veilshare.core.transfer.ReceiveResult.DuplicateChunk).chunkIndex)
    }

    @Test fun receiverRejectsOutOfOrderChunks() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-transfer")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()
        
        // Send chunk 1 first (out of order)
        val chunk1 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 1,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 2 },
            nonce = ByteArray(12) { 0 },
        )
        val result1 = receiver.receive(chunk1)
        assertTrue(result1 is dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted)
        
        // Then send chunk 0
        val chunk0 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 1 },
            nonce = ByteArray(12) { 0 },
        )
        val result0 = receiver.receive(chunk0)
        assertTrue(result0 is dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted)
        
        // Then send chunk 2
        val chunk2 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 2,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 3 },
            nonce = ByteArray(12) { 0 },
        )
        val result2 = receiver.receive(chunk2)
        assertTrue(result2 is dev.veilshare.core.transfer.ReceiveResult.TransferComplete)
    }
}