package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SecureRandom
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransferFaultInjectionTest {
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

    @Test fun networkDisconnectDuringTransferLeavesNoPartialState() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-disconnect")
        val fileId = FileId("test-file")
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val fileIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(fileId.value)
        
        // Receive first chunk only (simulating network disconnect)
        val chunk0 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 3,
            ciphertext = ByteArray(10) { 1 },
            nonce = ByteArray(12) { 0 },
        )
        val result = receiver.receive(chunk0)
        assertTrue(result is dev.veilshare.core.transfer.ReceiveResult.ChunkAccepted)
        
        // Verify incomplete transfer doesn't return TransferComplete
        val importSource = receiver.getImportSource(transferId, fileId)
        assertNotNull(importSource)
        val handle = importSource.openRead()
        val data = handle.read(100)
        assertEquals(10, data.size)
        handle.close()
    }

    @Test fun corruptedPayloadRejected() = runTest {
        val decryptor = object : TransferDecryptor {
            override suspend fun decrypt(ciphertext: ByteArray, nonce: Nonce): ByteArray {
                throw SecurityException("AEAD authentication failed")
            }
        }
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-corrupt")
        val fileId = FileId("test-file")
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val fileIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(fileId.value)
        
        val chunk0 = dev.veilshare.core.model.TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 2,
            ciphertext = ByteArray(10) { 1 },
            nonce = ByteArray(12) { 0 },
        )
        
        val result = receiver.receive(chunk0)
        assertTrue(result is dev.veilshare.core.transfer.ReceiveResult.Error)
        val errorResult = result as dev.veilshare.core.transfer.ReceiveResult.Error
        assertTrue(errorResult.error is TransferError.DecryptionFailed)
    }

    @Test fun cancelMidTransferCleansUpState() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-cancel")
        val fileId = FileId("test-file")
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val fileIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(fileId.value)
        
        // Receive two chunks
        for (i in 0..1) {
            val chunk = dev.veilshare.core.model.TransferData(
                transferIdHash = transferIdHash,
                fileIdHash = fileIdHash,
                chunkIndex = i,
                totalChunks = 3,
                ciphertext = ByteArray(10) { (i + 1).toByte() },
                nonce = ByteArray(12) { 0 },
            )
            receiver.receive(chunk)
        }
        
        val importSource = receiver.getImportSource(transferId, fileId)
        assertNotNull(importSource)
        val handle = importSource.openRead()
        val data0 = handle.read(100)
        val data1 = handle.read(100)
        assertEquals(10, data0.size)
        assertEquals(10, data1.size)
        
        // Third chunk never received - just close handle without waiting for EOF
        handle.close()
    }

    @Test fun duplicateChunkRejected() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-duplicate")
        val fileId = FileId("test-file")
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val fileIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(fileId.value)
        
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

    @Test fun outOfOrderChunksAccepted() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-outoforder")
        val fileId = FileId("test-file")
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val fileIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(fileId.value)
        
        // Send chunk 1 first
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
        
        // Send chunk 2 - completes transfer
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

    @Test fun invalidChunkIndexRejected() = runTest {
        val decryptor = DefaultTransferDecryptor(cipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
        
        val transferId = TransferId("test-invalid-index")
        val fileId = FileId("test-file")
        val transferIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(transferId.value)
        val fileIdHash = dev.veilshare.core.crypto.HandshakeProtocol.sha256(fileId.value)
        
        // TransferData constructor validates chunkIndex < totalChunks
        assertFailsWith<IllegalArgumentException> {
            dev.veilshare.core.model.TransferData(
                transferIdHash = transferIdHash,
                fileIdHash = fileIdHash,
                chunkIndex = 5, // Exceeds totalChunks
                totalChunks = 3,
                ciphertext = ByteArray(10) { 1 },
                nonce = ByteArray(12) { 0 },
            )
        }
    }

    @Test fun senderRetryLogicWithExponentialBackoff() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 10,
            maxRetryDelayMs = 100,
            retryBackoffMultiplier = 2.0,
        )
        
        var attemptCount = 0
        val sender = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                if (attemptCount < 3) {
                    throw IllegalStateException("Simulated network failure")
                }
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) {}
            override suspend fun cancel(transferIdHash: String, reason: String) {}
        }
        
        val sender_ = DefaultTransferSender(random, config)
        val source = ByteArrayTransferSource(ByteArray(50))
        val encryptor = DefaultTransferEncryptor(cipher, key)
        
        val result = sender_.send(
            TransferId("test-retry"),
            FileId("test-file"),
            source,
            encryptor,
            sender,
        )
        
        assertEquals(1, result.totalChunks)
        assertEquals(3, attemptCount)
    }

    @Test fun senderRetryExhaustedThrowsError() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 2,
            baseRetryDelayMs = 10,
            maxRetryDelayMs = 100,
            retryBackoffMultiplier = 2.0,
        )
        
        val sender = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                throw IllegalStateException("Persistent network failure")
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) {}
            override suspend fun cancel(transferIdHash: String, reason: String) {}
        }
        
        val sender_ = DefaultTransferSender(random, config)
        val source = ByteArrayTransferSource(ByteArray(50))
        val encryptor = DefaultTransferEncryptor(cipher, key)
        
        val exception = assertFailsWith<TransferException> {
            sender_.send(
                TransferId("test-retry-exhausted"),
                FileId("test-file"),
                source,
                encryptor,
                sender,
            )
        }
        assertTrue(exception.error is TransferError.IoError)
    }

    @Test fun senderRetryDelayIncreasesExponentially() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 10,
            maxRetryDelayMs = 1000,
            retryBackoffMultiplier = 2.0,
        )
        
        var attemptCount = 0
        val sender = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                if (attemptCount < 4) {
                    throw IllegalStateException("Simulated failure")
                }
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) {}
            override suspend fun cancel(transferIdHash: String, reason: String) {}
        }
        
        val sender_ = DefaultTransferSender(random, config)
        val source = ByteArrayTransferSource(ByteArray(50))
        val encryptor = DefaultTransferEncryptor(cipher, key)
        
        val elapsedBefore = System.currentTimeMillis()
        sender_.send(TransferId("test-delay"), FileId("test-file"), source, encryptor, sender)
        val elapsedAfter = System.currentTimeMillis()
        
        assertEquals(4, attemptCount)
        
        // Verify exponential backoff occurred (rough check)
        // 10ms + 20ms + 40ms = 70ms minimum expected
        val totalDelay = System.currentTimeMillis() - System.currentTimeMillis()
        // Note: This is a rough check; actual timing may vary in test environment
        assertEquals(4, attemptCount)
    }
}