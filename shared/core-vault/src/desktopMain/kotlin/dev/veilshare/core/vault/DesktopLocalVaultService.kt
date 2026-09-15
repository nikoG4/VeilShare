package dev.veilshare.core.vault

import dev.veilshare.core.crypto.AeadKeyWrapper
import dev.veilshare.core.crypto.Argon2Parameters
import dev.veilshare.core.crypto.Argon2Policy
import dev.veilshare.core.crypto.DesktopProductionCrypto
import dev.veilshare.core.crypto.SensitiveChars
import java.nio.file.Path

class DesktopLocalVaultService(private val root: Path) : LocalVaultService {
    private val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))

    override suspend fun storageState(): LocalStorageState = try {
        when (DesktopVaultSlotStore(root).all().size) {
            0 -> LocalStorageState.EMPTY
            2 -> LocalStorageState.READY
            else -> LocalStorageState.INCOMPLETE
        }
    } catch (_: Exception) { LocalStorageState.CORRUPT }

    override suspend fun createPair(primary: CharArray, alternate: CharArray) {
        require(primary.isNotEmpty() && alternate.isNotEmpty() && !primary.contentEquals(alternate))
        check(storageState() == LocalStorageState.EMPTY) { "Storage is not empty" }
        val crypto = DesktopProductionCrypto.create()
        val first = SensitiveChars(primary)
        val second = SensitiveChars(alternate)
        try {
            CreateVaultSetUseCase(
                DesktopVaultSlotStore(root), crypto.random, crypto.passwordKdf,
                AeadKeyWrapper(crypto.cipher), crypto.cipher, policy,
                DesktopVaultCatalogBootstrap(root, DesktopProductionCrypto.keyDeriver(), crypto.cipher),
            ).create(first, second)
        } finally {
            first.close(); second.close()
        }
    }

    override suspend fun unlock(credential: CharArray): LocalUnlockResult {
        val crypto = DesktopProductionCrypto.create()
        val slots = DesktopVaultSlotStore(root)
        val wrapper = AeadKeyWrapper(crypto.cipher)
        val chars = SensitiveChars(credential)
        val result = try {
            DesktopVaultRepository(
                root,
                UnlockVaultUseCase(slots, crypto.passwordKdf, wrapper, crypto.cipher, policy),
                DesktopProductionCrypto.keyDeriver(), crypto.cipher,
            ).open(chars)
        } finally { chars.close() }
        return when (result) {
            DesktopOpenResult.InvalidCredential -> LocalUnlockResult.InvalidCredential
            DesktopOpenResult.Corrupt -> LocalUnlockResult.Corrupt
            is DesktopOpenResult.Ready -> {
                val catalog = DesktopEncryptedCatalogStore(
                    root, result.session.descriptor.vaultId,
                    CatalogCrypto(DesktopProductionCrypto.keyDeriver(), crypto.cipher),
                )
                val journal = DesktopVaultJournal(root)
                val active = ActiveVault(
                    result.session, result.catalog, crypto.random, crypto.cipher,
                    DesktopProductionCrypto.keyDeriver(), catalog,
                    DesktopBlobStore(root.resolve("blobs"), result.session.descriptor.blobNamespace), journal,
                    ChangeCredentialUseCase(slots, crypto.random, crypto.passwordKdf, wrapper, policy),
                )
                val personaId = localPersonaIdFor(result.session.descriptor.vaultId)
                try { active.recover(); LocalUnlockResult.Ready(active, personaId) }
                catch (_: Exception) { active.close(); LocalUnlockResult.Corrupt }
            }
        }
    }
}
