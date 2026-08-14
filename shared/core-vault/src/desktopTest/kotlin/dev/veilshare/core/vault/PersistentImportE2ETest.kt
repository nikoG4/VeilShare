package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentImportE2ETest {
 @Test fun `real imported file survives fresh process objects and leaves no plaintext canary`()=runTest {
  val root=Files.createTempDirectory("veil-vault-"); val fixture=Files.createTempFile("EXTREMELY_SECRET_FILENAME_593821", ".txt"); val content="EXTREMELY_SECRET_CONTENT_194720".encodeToByteArray(); Files.write(fixture,content)
  val c=DesktopProductionCrypto.create(); val p=Argon2Policy(Argon2Parameters(8192,1,1)); val w=ImportWrapper(c.cipher)
  CreateVaultSetUseCase(DesktopVaultSlotStore(root),c.random,c.passwordKdf,w,c.cipher,p,DesktopVaultCatalogBootstrap(root,DesktopProductionCrypto.keyDeriver(),c.cipher)).create(SensitiveChars("123456".toCharArray()),SensitiveChars("654321".toCharArray()))
  val unlock=UnlockVaultUseCase(DesktopVaultSlotStore(root),c.passwordKdf,w,c.cipher,p); val open=DesktopVaultRepository(root,unlock,DesktopProductionCrypto.keyDeriver(),c.cipher); val ready=assertIs<DesktopOpenResult.Ready>(open.open(SensitiveChars("123456".toCharArray())))
  val store=DesktopEncryptedCatalogStore(root,ready.session.descriptor.vaultId,CatalogCrypto(DesktopProductionCrypto.keyDeriver(),c.cipher)); val journal=DesktopVaultJournal(root); val coordinator=ImportCoordinator(c.random,c.cipher,DesktopBlobStore(root.resolve("blobs"),ready.session.descriptor.blobNamespace),store,journal,FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),c.cipher)); coordinator.import(ready.session,ready.catalog,DesktopImportSource(fixture)); ready.session.close()
  val c2=DesktopProductionCrypto.create(); val open2=DesktopVaultRepository(root,UnlockVaultUseCase(DesktopVaultSlotStore(root),c2.passwordKdf,ImportWrapper(c2.cipher),c2.cipher,p),DesktopProductionCrypto.keyDeriver(),c2.cipher); val reopened=assertIs<DesktopOpenResult.Ready>(open2.open(SensitiveChars("123456".toCharArray()))); val file=assertIs<VaultItem.File>(reopened.catalog.entries.single())
  val fk=FileKeyWrapping(DesktopProductionCrypto.keyDeriver(),c2.cipher).unwrap(reopened.session.key(),file.wrappedFileKey!!,reopened.session.descriptor.vaultId,file.id,file.blobId); val output=ArrayList<Byte>(); EncryptedBlobReader(c2.cipher).read(DesktopBlobStore(root.resolve("blobs"),reopened.session.descriptor.blobNamespace).open(file.blobId),fk,file.id.value.encodeToByteArray()){it.forEach(output::add)}; assertEquals(content.toList(),output.toByteArray().toList()); fk.material.close(); reopened.session.close()
  val scan=ArrayList<Byte>(); Files.walk(root).use { paths -> paths.filter{Files.isRegularFile(it)}.forEach { path -> Files.readAllBytes(path).forEach(scan::add) } }; val all=scan.toByteArray(); assertFalse(all.contains("EXTREMELY_SECRET_FILENAME_593821".encodeToByteArray())); assertFalse(all.contains(content)); assertFalse(all.contains("text/plain".encodeToByteArray()))
 }
}
private class ImportWrapper(private val c:AuthenticatedCipher):KeyWrapper { override suspend fun wrap(k:KeyEncryptionKey,v:VaultKey,a:ByteArray)=c.seal(k.material,v.material.copy(),a); override suspend fun unwrap(k:KeyEncryptionKey,s:SealedBytes,a:ByteArray)=VaultKey(SensitiveBytes(c.open(k.material,s,a))) }
private fun ByteArray.contains(s:ByteArray):Boolean=(0..size-s.size).any { copyOfRange(it,it+s.size).contentEquals(s) }
