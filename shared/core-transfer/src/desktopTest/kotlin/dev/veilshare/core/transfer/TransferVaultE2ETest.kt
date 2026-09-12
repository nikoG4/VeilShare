package dev.veilshare.core.transfer

import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.model.FileId
import dev.veilshare.core.model.TransferData
import dev.veilshare.core.model.TransferId
import dev.veilshare.core.vault.DesktopLocalVaultService
import dev.veilshare.core.vault.LocalUnlockResult
import dev.veilshare.core.vault.VaultItem
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TransferVaultE2ETest {
    @Test
    fun `real encrypted multi-chunk transfer imports into persistent vault and survives reopen`() = runTest {
        val root = Files.createTempDirectory("transfer-vault-e2e-")
        val crypto = DesktopProductionCrypto.create()
        val sessionKey = SensitiveBytes(ByteArray(32) { index -> (index * 7 + 3).toByte() })
        val transferId = TransferId("e2e-transfer-001")
        val fileId = FileId("e2e-file-001")
        val plaintext = ByteArray(TransferProtocol.CHUNK_SIZE + 12_345) { index ->
            ((index * 31 + 17) and 0xff).toByte()
        }

        try {
            val receiver = InMemoryTransferReceiver(
                decryptor = DefaultTransferDecryptor(crypto.cipher, sessionKey),
            )
            val loopback = LoopbackNetwork(receiver)
            val sender = DefaultTransferSender(crypto.random)

            val result = sender.send(
                transferId = transferId,
                fileId = fileId,
                source = ByteArrayTransferSource(
                    data = plaintext,
                    displayName = "ignored-at-transport-layer.bin",
                    mimeHint = "application/octet-stream",
                ),
                encryptor = DefaultTransferEncryptor(crypto.cipher, sessionKey),
                sender = loopback,
            )

            assertEquals(2, result.totalChunks)
            assertEquals(plaintext.size.toLong(), result.totalBytes)
            assertIs<TransferReceiverProgress.TransferComplete>(receiver.getProgress(transferId).value)

            val service = DesktopLocalVaultService(root)
            service.createPair("3101".toCharArray(), "4102".toCharArray())
            val vault = assertIs<LocalUnlockResult.Ready>(service.unlock("3101".toCharArray())).vault
            val imported = ReceivedTransferVaultImporter(receiver).importCompleted(
                transferId = transferId,
                fileId = fileId,
                vault = vault,
                displayName = "received-e2e.bin",
                mimeHint = "application/octet-stream",
            )

            assertEquals("received-e2e.bin", imported.displayName)
            assertEquals("application/octet-stream", imported.mimeHint)
            assertEquals(plaintext.size.toLong(), imported.size)
            assertContentEquals(plaintext, readAll(vault, imported))
            vault.close()

            // Transfer ciphertext must be released once Vault has consumed the import source.
            assertFailsWith<IllegalStateException> {
                receiver.getImportSource(transferId, fileId)
            }

            // Reopen from persistent storage and verify the VBL1/catalog path, not only RAM state.
            val reopened = assertIs<LocalUnlockResult.Ready>(
                DesktopLocalVaultService(root).unlock("3101".toCharArray()),
            ).vault
            val durable = assertIs<VaultItem.File>(reopened.find(imported.id))
            assertEquals("received-e2e.bin", durable.displayName)
            assertContentEquals(plaintext, readAll(reopened, durable))
            reopened.close()
        } finally {
            sessionKey.close()
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid offered filename is rejected before consuming completed transfer`() = runTest {
        val root = Files.createTempDirectory("transfer-vault-name-")
        val crypto = DesktopProductionCrypto.create()
        val sessionKey = SensitiveBytes(ByteArray(32) { index -> (index + 11).toByte() })
        val transferId = TransferId("filename-transfer")
        val fileId = FileId("filename-file")
        val plaintext = "filename-metadata-check".encodeToByteArray()

        try {
            val receiver = InMemoryTransferReceiver(DefaultTransferDecryptor(crypto.cipher, sessionKey))
            DefaultTransferSender(crypto.random).send(
                transferId = transferId,
                fileId = fileId,
                source = ByteArrayTransferSource(plaintext),
                encryptor = DefaultTransferEncryptor(crypto.cipher, sessionKey),
                sender = LoopbackNetwork(receiver),
            )

            val service = DesktopLocalVaultService(root)
            service.createPair("5101".toCharArray(), "6102".toCharArray())
            val vault = assertIs<LocalUnlockResult.Ready>(service.unlock("5101".toCharArray())).vault
            val importer = ReceivedTransferVaultImporter(receiver)

            assertFailsWith<IllegalArgumentException> {
                importer.importCompleted(
                    transferId = transferId,
                    fileId = fileId,
                    vault = vault,
                    displayName = "../../unsafe.bin",
                )
            }

            // Validation occurs before getImportSource/openRead, so a corrected offer can retry.
            val item = importer.importCompleted(
                transferId = transferId,
                fileId = fileId,
                vault = vault,
                displayName = "safe.bin",
                mimeHint = "application/octet-stream",
            )
            assertContentEquals(plaintext, readAll(vault, item))
            vault.close()
        } finally {
            sessionKey.close()
            plaintext.fill(0)
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun readAll(
        vault: dev.veilshare.core.vault.VaultHandle,
        item: VaultItem.File,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        vault.readFile(item.id) { chunk -> output.write(chunk) }
        return output.toByteArray()
    }

    private class LoopbackNetwork(
        private val receiver: TransferReceiver,
    ) : TransferNetworkSender {
        override suspend fun send(data: TransferData) {
            when (val result = receiver.receive(data)) {
                is ReceiveResult.Error -> throw TransferException(result.error)
                else -> Unit
            }
        }

        override suspend fun complete(
            transferIdHash: String,
            fileIdHash: String,
            totalChunks: Int,
        ) = Unit

        override suspend fun cancel(transferIdHash: String, reason: String) {
            receiver.abort(transferIdHash, reason)
        }
    }
}
