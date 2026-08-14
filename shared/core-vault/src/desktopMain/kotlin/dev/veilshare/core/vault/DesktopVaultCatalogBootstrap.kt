package dev.veilshare.core.vault

import dev.veilshare.core.crypto.KeyDeriver
import java.nio.file.Path

class DesktopVaultCatalogBootstrap(root: Path, deriver: KeyDeriver, cipher: dev.veilshare.core.crypto.AuthenticatedCipher) : VaultCatalogBootstrap {
    private val root = root
    private val crypto = CatalogCrypto(deriver, cipher)
    override suspend fun create(descriptor: VaultDescriptor, vaultKey: dev.veilshare.core.crypto.VaultKey) {
        DesktopEncryptedCatalogStore(root, descriptor.vaultId, crypto).createEmpty(vaultKey)
    }
}
