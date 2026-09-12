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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransferSecurityHardeningTest {
    private val random = object : SecureRandom {
        override fun bytes(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }
    }

    private val identityCipher = object : AuthenticatedCipher {
        override suspend fun seal(
            key: SensitiveBytes,
            plaintext: ByteArray,
            aad: ByteArray,
        ): SealedBytes = SealedBytes(Nonce(random.bytes(12)), plaintext.copyOf())

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
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)
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

    // Fragmentation tests

    @Test
    fun senderFragmentsLargeChunk() = runTest {
        val config = TransferConfig(
            chunkSize = 1000, // Large enough to fit entire plaintext in one chunk
            maxCiphertextSize = 2000,
            maxTransportFramePayload = 200, // Small payload to force fragmentation
        )
        val sender = DefaultTransferSender(random, config)
        var sentData: MutableList<TransferData> = mutableListOf()

        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                sentData += data
            }

            override suspend fun complete(
                transferIdHash: String,
                fileIdHash: String,
                totalChunks: Int,
            ) = Unit

            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        val plaintext = ByteArray(150) { it.toByte() }
        val source = ByteArrayTransferSource(plaintext)

        sender.send(
            transferId = TransferId("frag-test"),
            fileId = FileId("frag-file"),
            source = source,
            encryptor = DefaultTransferEncryptor(identityCipher, key),
            sender = network,
        )

        // Should have fragmented the single chunk
        assertTrue(sentData.size >= 2)
        assertEquals(sentData[0].fragmentCount, sentData.size)
        assertEquals(1, sentData[0].totalChunks)
        for (i in sentData.indices) {
            assertEquals(i, sentData[i].fragmentIndex)
            assertEquals(0, sentData[i].chunkIndex)
        }
    }

    @Test
    fun receiverReassemblesFragments() = runTest {
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)

        val transferId = TransferId("frag-reassemble-test")
        val fileId = FileId("frag-reassemble-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        val plaintext = ByteArray(100) { it.toByte() }
        val nonce = ByteArray(12)
        val ciphertext = identityCipher.sealWithNonce(key, Nonce(nonce), plaintext, ByteArray(0)).ciphertext

        // Send in 2 fragments
        val fragmentCount = 2
        val fragmentSize = (ciphertext.size + fragmentCount - 1) / fragmentCount

        for (i in 0 until fragmentCount) {
            val start = i * fragmentSize
            val end = minOf(start + fragmentSize, ciphertext.size)
            val fragmentCiphertext = ciphertext.copyOfRange(start, end)

            val fragment = TransferData(
                transferIdHash = transferIdHash,
                fileIdHash = fileIdHash,
                chunkIndex = 0,
                totalChunks = 1,
                ciphertext = fragmentCiphertext,
                nonce = nonce,
                fragmentIndex = i,
                fragmentCount = fragmentCount,
            )

            val result = receiver.receive(fragment)
            if (i == fragmentCount - 1) {
                assertIs<ReceiveResult.TransferComplete>(result)
            } else {
                assertIs<ReceiveResult.ChunkAccepted>(result)
            }
        }

        // Verify reassembled data
        val handle = receiver.getImportSource(transferId, fileId).openRead()
        val decrypted = handle.read(200)
        handle.close()

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun receiverRejectsExcessiveFragments() = runTest {
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor)

        val transferId = TransferId("excessive-frag-test")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        val fragment = TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 1,
            ciphertext = ByteArray(10),
            nonce = ByteArray(12),
            fragmentIndex = 0,
            fragmentCount = TransferProtocol.MAX_FRAGMENTS_PER_CHUNK + 1,
        )

        val result = receiver.receive(fragment)
        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.InvalidTransferMetadata>(result.error)
    }

    @Test
    fun receiverRejectsInvalidFragmentIndex() = runTest {
        val transferId = TransferId("invalid-frag-index-test")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        // Invalid fragmentIndex is caught at TransferData construction time
        assertFailsWith<IllegalArgumentException> {
            TransferData(
                transferIdHash = transferIdHash,
                fileIdHash = fileIdHash,
                chunkIndex = 0,
                totalChunks = 1,
                ciphertext = ByteArray(10),
                nonce = ByteArray(12),
                fragmentIndex = 2,
                fragmentCount = 2,
            )
        }
    }

    // Resource cleanup tests

    @Test
    fun sourceClosedOnSuccess() = runTest {
        var closeCount = 0
        val source = object : TransferSource {
            override val fileSize: Long = 10
            override val displayName: String = "test"
            override val mimeHint: String? = null
            override suspend fun readChunk(offset: Long, size: Int): ByteArray = ByteArray(10)
            override suspend fun close() { closeCount++ }
        }

        val sender = DefaultTransferSender(random)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) = Unit
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        sender.send(
            transferId = TransferId("cleanup-success"),
            fileId = FileId("cleanup-file"),
            source = source,
            encryptor = DefaultTransferEncryptor(identityCipher, key),
            sender = network,
        )

        assertEquals(1, closeCount)
    }

    @Test
    fun sourceClosedOnTransportFailure() = runTest {
        var closeCount = 0
        val source = object : TransferSource {
            override val fileSize: Long = 10
            override val displayName: String = "test"
            override val mimeHint: String? = null
            override suspend fun readChunk(offset: Long, size: Int): ByteArray = ByteArray(10)
            override suspend fun close() { closeCount++ }
        }

        val sender = DefaultTransferSender(random)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) { throw IllegalStateException("Network error") }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<TransferException> {
            sender.send(
                transferId = TransferId("cleanup-failure"),
                fileId = FileId("cleanup-file"),
                source = source,
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }

        assertEquals(1, closeCount)
    }

    @Test
    fun sourceClosedOnCancellation() = runTest {
        var closeCount = 0
        val source = object : TransferSource {
            override val fileSize: Long = 10
            override val displayName: String = "test"
            override val mimeHint: String? = null
            override suspend fun readChunk(offset: Long, size: Int): ByteArray = ByteArray(10)
            override suspend fun close() { closeCount++ }
        }

        val config = TransferConfig(
            chunkSize = 16,
            maxRetries = 0,
            baseRetryDelayMs = 0,
            maxRetryDelayMs = 0,
        )
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) { throw CancellationException("Cancelled") }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<CancellationException> {
            sender.send(
                transferId = TransferId("cleanup-cancel"),
                fileId = FileId("cleanup-file"),
                source = source,
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }

        assertEquals(1, closeCount)
    }

    @Test
    fun sourceClosedOnCryptoFailure() = runTest {
        var closeCount = 0
        val source = object : TransferSource {
            override val fileSize: Long = 10
            override val displayName: String = "test"
            override val mimeHint: String? = null
            override suspend fun readChunk(offset: Long, size: Int): ByteArray = ByteArray(10)
            override suspend fun close() { closeCount++ }
        }

        val failingCipher = object : AuthenticatedCipher {
            override suspend fun seal(key: SensitiveBytes, plaintext: ByteArray, aad: ByteArray): SealedBytes =
                throw SecurityException("Crypto failed")
            override suspend fun sealWithNonce(key: SensitiveBytes, nonce: Nonce, plaintext: ByteArray, aad: ByteArray): SealedBytes =
                throw SecurityException("Crypto failed")
            override suspend fun open(key: SensitiveBytes, sealed: SealedBytes, aad: ByteArray): ByteArray =
                throw SecurityException("Crypto failed")
        }

        val sender = DefaultTransferSender(random)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) = Unit
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<SecurityException> {
            sender.send(
                transferId = TransferId("cleanup-crypto"),
                fileId = FileId("cleanup-file"),
                source = source,
                encryptor = DefaultTransferEncryptor(failingCipher, key),
                sender = network,
            )
        }

        assertEquals(1, closeCount)
    }

    // Limit tests

    @Test
    fun receiverRejectsThirdActiveTransfer() = runTest {
        val config = TransferConfig(maxActiveTransfers = 2)
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor, config)

        val fileId = FileId("test-file")
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        // First transfer
        val transferId1 = TransferId("transfer-1")
        val hash1 = Hash.sha256(transferId1.value.encodeToByteArray()).toHex()
        receiver.receive(TransferData(transferIdHash = hash1, fileIdHash = fileIdHash, chunkIndex = 0, totalChunks = 1, ciphertext = ByteArray(10), nonce = ByteArray(12)))

        // Second transfer
        val transferId2 = TransferId("transfer-2")
        val hash2 = Hash.sha256(transferId2.value.encodeToByteArray()).toHex()
        receiver.receive(TransferData(transferIdHash = hash2, fileIdHash = fileIdHash, chunkIndex = 0, totalChunks = 1, ciphertext = ByteArray(10), nonce = ByteArray(12)))

        // Third transfer should be rejected
        val transferId3 = TransferId("transfer-3")
        val hash3 = Hash.sha256(transferId3.value.encodeToByteArray()).toHex()
        val result = receiver.receive(TransferData(transferIdHash = hash3, fileIdHash = fileIdHash, chunkIndex = 0, totalChunks = 1, ciphertext = ByteArray(10), nonce = ByteArray(12)))

        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.TooManyActiveTransfers>(result.error)
    }

    @Test
    fun receiverAcceptsTransferAtMaxBytes() = runTest {
        val config = TransferConfig(maxTransferBytes = 100)
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor, config)

        val transferId = TransferId("max-bytes-test")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        // Send exactly 100 bytes (within limit)
        val result = receiver.receive(TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 1,
            ciphertext = ByteArray(100),
            nonce = ByteArray(12),
        ))

        assertIs<ReceiveResult.TransferComplete>(result)
    }

    @Test
    fun receiverRejectsTransferOverMaxBytes() = runTest {
        val config = TransferConfig(maxTransferBytes = 100)
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor, config)

        val transferId = TransferId("over-max-bytes-test")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        // Send 101 bytes (over limit)
        val result = receiver.receive(TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 1,
            ciphertext = ByteArray(101),
            nonce = ByteArray(12),
        ))

        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.TransferTooLarge>(result.error)
    }

    @Test
    fun receiverRejectsOversizedCiphertext() = runTest {
        val config = TransferConfig(maxCiphertextSize = 50)
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor, config)

        val transferId = TransferId("oversized-test")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        // Send ciphertext larger than maxCiphertextSize
        val result = receiver.receive(TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 1,
            ciphertext = ByteArray(51),
            nonce = ByteArray(12),
        ))

        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.ChunkTooLarge>(result.error)
    }

    @Test
    fun receiverRejectsMaxChunksPlusOne() = runTest {
        val config = TransferConfig(maxChunks = 10)
        val decryptor = DefaultTransferDecryptor(identityCipher, key)
        val receiver = InMemoryTransferReceiver(decryptor, config)

        val transferId = TransferId("max-chunks-test")
        val fileId = FileId("test-file")
        val transferIdHash = Hash.sha256(transferId.value.encodeToByteArray()).toHex()
        val fileIdHash = Hash.sha256(fileId.value.encodeToByteArray()).toHex()

        // Send chunk with totalChunks = maxChunks + 1
        val result = receiver.receive(TransferData(
            transferIdHash = transferIdHash,
            fileIdHash = fileIdHash,
            chunkIndex = 0,
            totalChunks = 11,
            ciphertext = ByteArray(10),
            nonce = ByteArray(12),
        ))

        assertIs<ReceiveResult.Error>(result)
        assertIs<TransferError.InvalidTransferMetadata>(result.error)
    }

    // Retry tests

    @Test
    fun senderRetriesOnTransientTransportFailure() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 10,
            maxRetryDelayMs = 100,
        )
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                if (attemptCount < 3) {
                    throw IllegalStateException("Transient network failure")
                }
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        val source = ByteArrayTransferSource(ByteArray(50))
        val result = sender.send(
            transferId = TransferId("retry-test"),
            fileId = FileId("retry-file"),
            source = source,
            encryptor = DefaultTransferEncryptor(identityCipher, key),
            sender = network,
        )

        assertEquals(1, result.totalChunks)
        assertEquals(3, attemptCount)
    }

    @Test
    fun senderDoesNotRetryCancellationException() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 0,
            maxRetryDelayMs = 0,
        )
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                throw CancellationException("cancel")
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<CancellationException> {
            sender.send(
                transferId = TransferId("cancel-retry"),
                fileId = FileId("cancel-file"),
                source = ByteArrayTransferSource(ByteArray(50)),
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }
        assertEquals(1, attemptCount)
    }

    @Test
    fun senderDoesNotRetrySecurityException() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 0,
            maxRetryDelayMs = 0,
        )
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                throw SecurityException("Security error")
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<SecurityException> {
            sender.send(
                transferId = TransferId("security-retry"),
                fileId = FileId("security-file"),
                source = ByteArrayTransferSource(ByteArray(50)),
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }
        assertEquals(1, attemptCount)
    }

    @Test
    fun senderDoesNotRetryIllegalArgumentException() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 0,
            maxRetryDelayMs = 0,
        )
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                throw IllegalArgumentException("Invalid argument")
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<IllegalArgumentException> {
            sender.send(
                transferId = TransferId("illegal-arg-retry"),
                fileId = FileId("illegal-arg-file"),
                source = ByteArrayTransferSource(ByteArray(50)),
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }
        assertEquals(1, attemptCount)
    }

    @Test
    fun senderDoesNotRetryNonIoTransferException() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 0,
            maxRetryDelayMs = 0,
        )
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                throw TransferException(TransferError.DecryptionFailed(0))
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        assertFailsWith<TransferException> {
            sender.send(
                transferId = TransferId("non-io-retry"),
                fileId = FileId("non-io-file"),
                source = ByteArrayTransferSource(ByteArray(50)),
                encryptor = DefaultTransferEncryptor(identityCipher, key),
                sender = network,
            )
        }
        assertEquals(1, attemptCount)
    }

    @Test
    fun senderRetriesOnIoTransferException() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 10,
            maxRetryDelayMs = 100,
        )
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                if (attemptCount < 3) {
                    throw TransferException(TransferError.IoError("IO failure"))
                }
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        val source = ByteArrayTransferSource(ByteArray(50))
        val result = sender.send(
            transferId = TransferId("io-retry-test"),
            fileId = FileId("io-retry-file"),
            source = source,
            encryptor = DefaultTransferEncryptor(identityCipher, key),
            sender = network,
        )

        assertEquals(1, result.totalChunks)
        assertEquals(3, attemptCount)
    }

    @Test
    fun senderReusesSameCiphertextOnRetry() = runTest {
        val config = TransferConfig(
            chunkSize = 100,
            maxRetries = 3,
            baseRetryDelayMs = 10,
            maxRetryDelayMs = 100,
        )
        var capturedCiphertext: ByteArray? = null
        var attemptCount = 0
        val sender = DefaultTransferSender(random, config)
        val network = object : TransferNetworkSender {
            override suspend fun send(data: TransferData) {
                attemptCount++
                if (attemptCount == 1) {
                    capturedCiphertext = data.ciphertext.copyOf()
                } else {
                    // Verify same ciphertext is retransmitted
                    assertContentEquals(capturedCiphertext!!, data.ciphertext)
                }
                if (attemptCount < 3) {
                    throw IllegalStateException("Transient failure")
                }
            }
            override suspend fun complete(transferIdHash: String, fileIdHash: String, totalChunks: Int) = Unit
            override suspend fun cancel(transferIdHash: String, reason: String) = Unit
        }

        val source = ByteArrayTransferSource(ByteArray(50))
        sender.send(
            transferId = TransferId("retry-reuse-test"),
            fileId = FileId("retry-reuse-file"),
            source = source,
            encryptor = DefaultTransferEncryptor(identityCipher, key),
            sender = network,
        )

        assertEquals(3, attemptCount)
        assertNotNull(capturedCiphertext)
    }
}