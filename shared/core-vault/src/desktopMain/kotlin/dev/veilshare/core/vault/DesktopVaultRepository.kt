package dev.veilshare.core.vault

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.SensitiveChars
import java.nio.file.Path

sealed interface DesktopOpenResult { data class Ready(val session: VaultSession, val catalog: CatalogSnapshot) : DesktopOpenResult; data object InvalidCredential : DesktopOpenResult; data object Corrupt : DesktopOpenResult }
/** Desktop composition root: slot authentication must be followed by authenticated catalog loading. */
class DesktopVaultRepository(private val root: Path, private val unlock: UnlockVaultUseCase, deriver: KeyDeriver, cipher: AuthenticatedCipher) {
    private val crypto = CatalogCrypto(deriver, cipher)
    suspend fun open(pin: SensitiveChars): DesktopOpenResult = when (val result = unlock.unlock(pin)) {
        is UnlockResult.Success -> try { DesktopOpenResult.Ready(result.session, DesktopEncryptedCatalogStore(root, result.session.descriptor.vaultId, crypto).load(result.session.key())) }
            catch (_: Exception) { result.session.close(); DesktopOpenResult.Corrupt }
        else -> DesktopOpenResult.InvalidCredential
    }
}
