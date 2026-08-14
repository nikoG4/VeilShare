package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChangeCredentialE2ETest { @Test fun `changing real credential preserves namespace catalog and blobs`()=runTest {
 val root=Files.createTempDirectory("veil-pin-");val source=Files.createTempFile("pin-",".txt");Files.write(source,"pin rotation content".encodeToByteArray());val c=DesktopProductionCrypto.create();val p=Argon2Policy(Argon2Parameters(8192,1,1));val w=RotationWrapper(c.cipher);CreateVaultSetUseCase(DesktopVaultSlotStore(root),c.random,c.passwordKdf,w,c.cipher,p,DesktopVaultCatalogBootstrap(root,DesktopProductionCrypto.keyDeriver(),c.cipher)).create(SensitiveChars("000123".toCharArray()),SensitiveChars("000124".toCharArray()))
 fun repo(pin:String)=DesktopVaultRepository(root,UnlockVaultUseCase(DesktopVaultSlotStore(root),c.passwordKdf,w,c.cipher,p),DesktopProductionCrypto.keyDeriver(),c.cipher)
 val real=assertIs<DesktopOpenResult.Ready>(repo("000123").open(SensitiveChars("000123".toCharArray())));val store=DesktopBlobStore(root.resolve("blobs"),real.session.descriptor.blobNamespace);ImportCoordinator(c.random,c.cipher,store,DesktopEncryptedCatalogStore(root,real.session.descriptor.vaultId,CatalogCrypto(DesktopProductionCrypto.keyDeriver(),c.cipher)),DesktopVaultJournal(root),FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),c.cipher)).import(real.session,real.catalog,DesktopImportSource(source));val blobHash=hash(Files.walk(root.resolve("blobs")).filter{Files.isRegularFile(it)}.findFirst().get());val catalog=Files.walk(root.resolve("catalogs")).filter{Files.isRegularFile(it)}.findFirst().get();val catalogHash=hash(catalog);val ns=real.session.descriptor.blobNamespace;ChangeCredentialUseCase(DesktopVaultSlotStore(root),c.random,c.passwordKdf,w,p).change(real.session,SensitiveChars("999999".toCharArray()));real.session.close()
 assertIs<UnlockResult.InvalidCredential>(UnlockVaultUseCase(DesktopVaultSlotStore(root),c.passwordKdf,w,c.cipher,p).unlock(SensitiveChars("000123".toCharArray())));val after=assertIs<DesktopOpenResult.Ready>(repo("999999").open(SensitiveChars("999999".toCharArray())));assertEquals(ns,after.session.descriptor.blobNamespace);assertEquals(blobHash,hash(Files.walk(root.resolve("blobs")).filter{Files.isRegularFile(it)}.findFirst().get()));assertEquals(catalogHash,hash(catalog));after.session.close();assertIs<DesktopOpenResult.Ready>(repo("000124").open(SensitiveChars("000124".toCharArray())))
} }
private fun hash(path:java.nio.file.Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){it.toUByte().toString(16).padStart(2,'0')}
private class RotationWrapper(private val c:AuthenticatedCipher):KeyWrapper {override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=c.seal(kek.material,vaultKey.material.copy(),aad);override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(c.open(kek.material,wrapped,aad)))}
