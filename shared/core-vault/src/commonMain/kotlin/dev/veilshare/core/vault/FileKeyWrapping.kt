package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import dev.veilshare.core.model.BlobId
import dev.veilshare.core.model.VaultId

class FileKeyWrapping(private val deriver: KeyDeriver, private val cipher: AuthenticatedCipher) {
    suspend fun wrap(vmk: VaultKey, fileKey: FileKey, vault: VaultId, item: VaultItemId, blob: BlobId): SealedBytes {
        val key=deriver.derive(vmk.material,CryptoContexts.FileKeyWrap); try { return cipher.seal(key,fileKey.material.copy(),aad(vault,item,blob)) } finally { key.close() }
    }
    suspend fun unwrap(vmk: VaultKey, wrapped: SealedBytes, vault: VaultId, item: VaultItemId, blob: BlobId): FileKey {
        val key=deriver.derive(vmk.material,CryptoContexts.FileKeyWrap); try { return FileKey(SensitiveBytes(cipher.open(key,wrapped,aad(vault,item,blob)))) } finally { key.close() }
    }
    private fun aad(v:VaultId,i:VaultItemId,b:BlobId)=("VEIL/V1/FILEKEY-WRAP|1|${v.value}|${i.value}|${b.value}|VEIL_CRYPTO_V1").encodeToByteArray()
}
