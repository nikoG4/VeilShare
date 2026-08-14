package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentCatalogE2ETest {
    @Test fun `real and decoy empty catalogs survive complete repository recreation`() = runTest {
        val root = Files.createTempDirectory("veil-catalog-e2e-"); val crypto = DesktopProductionCrypto.create(); val policy=Argon2Policy(Argon2Parameters(8192,1,1))
        val wrapper=CatalogTestWrapper(crypto.cipher)
        val slots=DesktopVaultSlotStore(root)
        CreateVaultSetUseCase(slots,crypto.random,crypto.passwordKdf,wrapper,crypto.cipher,policy,DesktopVaultCatalogBootstrap(root,DesktopProductionCrypto.keyDeriver(),crypto.cipher)).create(SensitiveChars("111111".toCharArray()),SensitiveChars("222222".toCharArray()))
        val freshUnlock=UnlockVaultUseCase(DesktopVaultSlotStore(root),crypto.passwordKdf,wrapper,crypto.cipher,policy)
        val repository=DesktopVaultRepository(root,freshUnlock,DesktopProductionCrypto.keyDeriver(),crypto.cipher)
        val real=assertIs<DesktopOpenResult.Ready>(repository.open(SensitiveChars("111111".toCharArray()))); assertEquals(VaultType.REAL,real.session.descriptor.type); assertTrue(real.catalog.entries.isEmpty()); real.session.close()
        val decoy=assertIs<DesktopOpenResult.Ready>(repository.open(SensitiveChars("222222".toCharArray()))); assertEquals(VaultType.DECOY,decoy.session.descriptor.type); assertTrue(decoy.catalog.entries.isEmpty()); decoy.session.close()
    }
}
private class CatalogTestWrapper(private val c:AuthenticatedCipher):KeyWrapper { override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=c.seal(kek.material,vaultKey.material.copy(),aad); override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(c.open(kek.material,wrapped,aad))) }
