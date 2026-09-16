package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportDeleteOriginalOrderingTest {
    @Test
    fun `requested original deletion occurs only after encrypted catalog is durable`() = runTest {
        val root = Files.createTempDirectory("veil-delete-order-")
        val events = mutableListOf<String>()
        val crypto = DesktopProductionCrypto.create()
        val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))
        val wrapper = OrderingWrapper(crypto.cipher)
        CreateVaultSetUseCase(
            DesktopVaultSlotStore(root),
            crypto.random,
            crypto.passwordKdf,
            wrapper,
            crypto.cipher,
            policy,
            DesktopVaultCatalogBootstrap(root, DesktopProductionCrypto.keyDeriver(), crypto.cipher),
        ).create(SensitiveChars("111111".toCharArray()), SensitiveChars("222222".toCharArray()))
        val opened = assertIs<DesktopOpenResult.Ready>(
            DesktopVaultRepository(
                root,
                UnlockVaultUseCase(
                    DesktopVaultSlotStore(root),
                    crypto.passwordKdf,
                    wrapper,
                    crypto.cipher,
                    policy,
                ),
                DesktopProductionCrypto.keyDeriver(),
                crypto.cipher,
            ).open(SensitiveChars("111111".toCharArray())),
        )
        val source = OrderingSource("payload".encodeToByteArray()) { events += "delete" }
        val coordinator = ImportCoordinator(
            crypto.random,
            crypto.cipher,
            DesktopBlobStore(root.resolve("blobs"), opened.session.descriptor.blobNamespace),
            DesktopEncryptedCatalogStore(
                root,
                opened.session.descriptor.vaultId,
                CatalogCrypto(DesktopProductionCrypto.keyDeriver(), crypto.cipher),
            ),
            DesktopVaultJournal(root),
            FileKeyWrapping(DesktopProductionCrypto.keyDeriver(), crypto.cipher),
        ) { point ->
            if (point == ImportFaultPoint.CatalogDurable) events += "catalog-durable"
        }

        coordinator.import(opened.session, opened.catalog, source) { progress ->
            if (progress is ImportProgress.Complete) events += "complete"
        }

        assertEquals(listOf("catalog-durable", "delete", "complete"), events)
        assertTrue(source.deleted)
        opened.session.close()
    }
}

private class OrderingSource(
    private val bytes: ByteArray,
    private val onDelete: () -> Unit,
) : ImportSource {
    override val displayName = "source.bin"
    override val mimeHint = "application/octet-stream"
    override val sizeHint = bytes.size.toLong()
    override val deleteOriginalRequested = true
    var deleted = false
        private set

    override suspend fun deleteOriginalAfterCommit(): Boolean {
        onDelete()
        deleted = true
        return true
    }

    override suspend fun openRead() = object : ImportReadHandle {
        private var done = false
        override suspend fun read(maxBytes: Int): ByteArray =
            if (done) ByteArray(0) else bytes.also { done = true }
        override suspend fun close() = Unit
    }
}

private class OrderingWrapper(private val cipher: AuthenticatedCipher) : KeyWrapper {
    override suspend fun wrap(kek: KeyEncryptionKey, vaultKey: VaultKey, aad: ByteArray) =
        cipher.seal(kek.material, vaultKey.material.copy(), aad)

    override suspend fun unwrap(kek: KeyEncryptionKey, wrapped: SealedBytes, aad: ByteArray) =
        VaultKey(SensitiveBytes(cipher.open(kek.material, wrapped, aad)))
}
