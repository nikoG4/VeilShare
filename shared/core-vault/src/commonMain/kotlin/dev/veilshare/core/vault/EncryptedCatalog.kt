package dev.veilshare.core.vault

import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.CryptoContexts
import dev.veilshare.core.crypto.KeyDeriver
import dev.veilshare.core.crypto.Nonce
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.VaultKey

@JvmInline value class CatalogSchemaVersion(val value:Int) { init { require(value >= 1) } }
data class CatalogSnapshot(val schema: CatalogSchemaVersion = CatalogSchemaVersion(1), val entries: List<VaultItem> = emptyList())
data class EncryptedCatalog(val schema: CatalogSchemaVersion, val payload: SealedBytes)
interface EncryptedCatalogStore { suspend fun exists():Boolean; suspend fun createEmpty(vaultKey:VaultKey); suspend fun load(vaultKey:VaultKey):CatalogSnapshot; suspend fun replaceAtomically(vaultKey:VaultKey,snapshot:CatalogSnapshot) }
/** Catalog encryption orchestration stays common; serializers and physical files are platform adapters. */
class CatalogCrypto(private val deriver:KeyDeriver, private val cipher:AuthenticatedCipher) {
    suspend fun encrypt(vaultKey:VaultKey, plaintext:ByteArray):EncryptedCatalog { val key=deriver.derive(vaultKey.material,CryptoContexts.Catalog); try { return EncryptedCatalog(CatalogSchemaVersion(1),cipher.seal(key,plaintext,CryptoContexts.Catalog)) } finally { key.close() } }
    suspend fun decrypt(vaultKey:VaultKey, encrypted:EncryptedCatalog):ByteArray { require(encrypted.schema.value==1); val key=deriver.derive(vaultKey.material,CryptoContexts.Catalog); try { return cipher.open(key,encrypted.payload,CryptoContexts.Catalog) } finally { key.close() } }
}
