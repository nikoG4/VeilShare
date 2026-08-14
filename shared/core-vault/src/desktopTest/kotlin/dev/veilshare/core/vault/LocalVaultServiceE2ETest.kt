package dev.veilshare.core.vault

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalVaultServiceE2ETest {
    @Test fun `production facade supports setup folder import reopen delete and credential change`() = runTest {
        val root=Files.createTempDirectory("ui-service-");val source=Files.createTempFile("ui-import-",".txt")
        val plain="UI_LOCAL_PIPELINE_EXACT".encodeToByteArray();Files.write(source,plain)
        var service=DesktopLocalVaultService(root);assertEquals(LocalStorageState.EMPTY,service.storageState())
        service.createPair("1001".toCharArray(),"2002".toCharArray());assertEquals(LocalStorageState.READY,service.storageState())
        val real=assertIs<LocalUnlockResult.Ready>(service.unlock("1001".toCharArray())).vault
        val directory=real.createDirectory(null,"Trabajo")
        val file=real.import(DesktopImportSource(source),VaultDirectoryId(directory.id.value))
        val output=ArrayList<Byte>();real.readFile(file.id){it.forEach(output::add)};assertContentEquals(plain,output.toByteArray());real.close()

        service=DesktopLocalVaultService(root)
        val reopened=assertIs<LocalUnlockResult.Ready>(service.unlock("1001".toCharArray())).vault
        assertEquals("Trabajo",reopened.items(null).single().displayName)
        val durable=assertIs<VaultItem.File>(reopened.items(VaultDirectoryId(directory.id.value)).single())
        val again=ArrayList<Byte>();reopened.readFile(durable.id){it.forEach(again::add)};assertContentEquals(plain,again.toByteArray())
        reopened.changeCredential("3003".toCharArray());reopened.close()
        assertIs<LocalUnlockResult.InvalidCredential>(DesktopLocalVaultService(root).unlock("1001".toCharArray()))
        val rotated=assertIs<LocalUnlockResult.Ready>(DesktopLocalVaultService(root).unlock("3003".toCharArray())).vault
        rotated.deleteFile(durable.id);assertTrue(rotated.items(VaultDirectoryId(directory.id.value)).isEmpty());rotated.close()

        val alternate=assertIs<LocalUnlockResult.Ready>(DesktopLocalVaultService(root).unlock("2002".toCharArray())).vault
        assertTrue(alternate.items(null).isEmpty());alternate.close()
    }

    @Test fun `active recovery authenticates durable catalog before garbage authority`() = runTest {
        val root=Files.createTempDirectory("ui-recovery-order-");val source=Files.createTempFile("ui-commit-",".txt");Files.write(source,"committed".encodeToByteArray())
        val service=DesktopLocalVaultService(root);service.createPair("7001".toCharArray(),"7002".toCharArray())
        val first=assertIs<LocalUnlockResult.Ready>(service.unlock("7001".toCharArray())).vault
        val file=first.import(DesktopImportSource(source));first.close()
        val crypto=dev.veilshare.core.crypto.DesktopProductionCrypto.create();val policy=dev.veilshare.core.crypto.Argon2Policy(dev.veilshare.core.crypto.Argon2Parameters(8192,1,1))
        val slots=DesktopVaultSlotStore(root);val wrapper=dev.veilshare.core.crypto.AeadKeyWrapper(crypto.cipher)
        val opened=assertIs<DesktopOpenResult.Ready>(DesktopVaultRepository(root,UnlockVaultUseCase(slots,crypto.passwordKdf,wrapper,crypto.cipher,policy),dev.veilshare.core.crypto.DesktopProductionCrypto.keyDeriver(),crypto.cipher).open(dev.veilshare.core.crypto.SensitiveChars("7001".toCharArray())))
        val catalog=DesktopEncryptedCatalogStore(root,opened.session.descriptor.vaultId,CatalogCrypto(dev.veilshare.core.crypto.DesktopProductionCrypto.keyDeriver(),crypto.cipher));val journal=DesktopVaultJournal(root);val blobs=DesktopBlobStore(root.resolve("blobs"),opened.session.descriptor.blobNamespace)
        journal.put(JournalEntry(TransactionId("stale-ui"),TransactionState.VERIFYING,setOf(file.blobId),opened.session.descriptor.blobNamespace))
        val stale=ActiveVault(opened.session,CatalogSnapshot(),crypto.random,crypto.cipher,dev.veilshare.core.crypto.DesktopProductionCrypto.keyDeriver(),catalog,blobs,journal,ChangeCredentialUseCase(slots,crypto.random,crypto.passwordKdf,wrapper,policy))
        stale.recover();assertTrue(blobs.exists(file.blobId));assertTrue(stale.items(null).any{it.id==file.id});assertTrue(journal.entries().isEmpty());stale.close()
    }
}
