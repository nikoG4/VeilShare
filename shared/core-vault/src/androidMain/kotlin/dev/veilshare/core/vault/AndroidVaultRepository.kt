package dev.veilshare.core.vault

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.SensitiveChars
import java.io.File

sealed interface AndroidOpenResult {
    data class Ready(val session: VaultSession, val catalog: CatalogSnapshot) : AndroidOpenResult
    data object InvalidCredential : AndroidOpenResult
    data object Corrupt : AndroidOpenResult
}

class AndroidVaultCatalogBootstrap(
    private val root: File,
    deriver: KeyDeriver,
    cipher: AuthenticatedCipher,
) : VaultCatalogBootstrap {
    private val crypto = CatalogCrypto(deriver, cipher)
    override suspend fun create(descriptor: VaultDescriptor, vaultKey: dev.veilshare.core.crypto.VaultKey) {
        AndroidEncryptedCatalogStore(root, descriptor.vaultId, crypto).createEmpty(vaultKey)
    }
}

class AndroidVaultRepository(
    private val root: File,
    private val unlock: UnlockVaultUseCase,
    deriver: KeyDeriver,
    cipher: AuthenticatedCipher,
) {
    private val crypto = CatalogCrypto(deriver, cipher)
    suspend fun open(pin: SensitiveChars): AndroidOpenResult = when (val result = unlock.unlock(pin)) {
        is UnlockResult.Success -> try {
            AndroidOpenResult.Ready(
                result.session,
                AndroidEncryptedCatalogStore(root, result.session.descriptor.vaultId, crypto).load(result.session.key()),
            )
        } catch (_: Exception) {
            result.session.close()
            AndroidOpenResult.Corrupt
        }
        else -> AndroidOpenResult.InvalidCredential
    }
}
