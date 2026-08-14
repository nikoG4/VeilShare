package dev.veilshare.core.vault

import dev.veilshare.core.crypto.AeadKeyWrapper
import dev.veilshare.core.crypto.AndroidProductionCrypto
import dev.veilshare.core.crypto.Argon2Parameters
import dev.veilshare.core.crypto.Argon2Policy
import dev.veilshare.core.crypto.SensitiveChars
import java.io.File

class AndroidLocalVaultService(private val root: File) : LocalVaultService {
    private val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))

    override suspend fun storageState(): LocalStorageState = try {
        when (AndroidVaultSlotStore(root).all().size) {
            0 -> LocalStorageState.EMPTY
            2 -> LocalStorageState.READY
            else -> LocalStorageState.INCOMPLETE
        }
    } catch (_: Exception) { LocalStorageState.CORRUPT }

    override suspend fun createPair(primary: CharArray, alternate: CharArray) {
        require(primary.isNotEmpty() && alternate.isNotEmpty() && !primary.contentEquals(alternate))
        check(storageState() == LocalStorageState.EMPTY) { "Storage is not empty" }
        val crypto = AndroidProductionCrypto.create()
        val first = SensitiveChars(primary); val second = SensitiveChars(alternate)
        try {
            CreateVaultSetUseCase(
                AndroidVaultSlotStore(root), crypto.random, crypto.passwordKdf,
                AeadKeyWrapper(crypto.cipher), crypto.cipher, policy,
                AndroidVaultCatalogBootstrap(root, AndroidProductionCrypto.keyDeriver(), crypto.cipher),
            ).create(first, second)
        } finally { first.close(); second.close() }
    }

    override suspend fun unlock(credential: CharArray): LocalUnlockResult {
        val crypto = AndroidProductionCrypto.create()
        val slots = AndroidVaultSlotStore(root)
        val wrapper = AeadKeyWrapper(crypto.cipher)
        val chars = SensitiveChars(credential)
        val result = try {
            AndroidVaultRepository(
                root, UnlockVaultUseCase(slots, crypto.passwordKdf, wrapper, crypto.cipher, policy),
                AndroidProductionCrypto.keyDeriver(), crypto.cipher,
            ).open(chars)
        } finally { chars.close() }
        return when (result) {
            AndroidOpenResult.InvalidCredential -> LocalUnlockResult.InvalidCredential
            AndroidOpenResult.Corrupt -> LocalUnlockResult.Corrupt
            is AndroidOpenResult.Ready -> {
                val catalog = AndroidEncryptedCatalogStore(
                    root, result.session.descriptor.vaultId,
                    CatalogCrypto(AndroidProductionCrypto.keyDeriver(), crypto.cipher),
                )
                val journal = AndroidVaultJournal(root)
                val active = ActiveVault(
                    result.session, result.catalog, crypto.random, crypto.cipher,
                    AndroidProductionCrypto.keyDeriver(), catalog,
                    AndroidBlobStore(File(root, "blobs"), result.session.descriptor.blobNamespace), journal,
                    ChangeCredentialUseCase(slots, crypto.random, crypto.passwordKdf, wrapper, policy),
                )
                try { active.recover(); LocalUnlockResult.Ready(active) }
                catch (_: Exception) { active.close(); LocalUnlockResult.Corrupt }
            }
        }
    }
}
