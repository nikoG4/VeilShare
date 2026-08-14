package dev.veilshare.ui.features

import dev.veilshare.core.model.BlobId
import dev.veilshare.core.vault.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalAppControllerTest {
    @Test fun startupSetupAndGenericDualCredentialUnlock() = runTest {
        val service = FakeService(LocalStorageState.EMPTY)
        val controller = controller(service)
        controller.initialize()
        assertIs<RootState.FirstRun>(controller.state.value)
        val p="1111".toCharArray(); val pc="1111".toCharArray(); val a="2222".toCharArray(); val ac="2222".toCharArray()
        controller.setup(p,pc,a,ac); advanceUntilIdle()
        assertIs<RootState.Locked>(controller.state.value)
        assertTrue(listOf(p,pc,a,ac).all { chars -> chars.all { it=='\u0000' } })

        controller.unlock("1111".toCharArray()); advanceUntilIdle()
        val first=assertIs<RootState.Unlocked>(controller.state.value); assertTrue(first.browser.items.any { it.name=="primary.txt" })
        controller.lock(); controller.unlock("2222".toCharArray()); advanceUntilIdle()
        val second=assertIs<RootState.Unlocked>(controller.state.value); assertTrue(second.browser.items.any { it.name=="alternate.txt" })
        assertFalse(second.browser.toString().contains("decoy", ignoreCase=true))
        assertFalse(second.browser.toString().contains("real", ignoreCase=true))
    }

    @Test fun wrongCredentialIsNeutralAndDoubleSubmitIsIgnored() = runTest {
        val service=FakeService(LocalStorageState.READY);val controller=controller(service);controller.initialize()
        controller.unlock("bad".toCharArray());controller.unlock("1111".toCharArray());advanceUntilIdle()
        val locked=assertIs<RootState.Locked>(controller.state.value)
        assertEquals("No se pudo continuar.",locked.error);assertEquals(1,service.unlockCalls)
    }

    @Test fun browserFolderImportDeleteAndPinChangeRefreshAuthoritativeState() = runTest {
        val service=FakeService(LocalStorageState.READY);val source=BytesSource("nuevo.txt","nuevo".encodeToByteArray())
        val dispatcher=StandardTestDispatcher(testScheduler);val controller=LocalAppController(service,object:LocalFilePicker{override suspend fun pick()=source},NoopOpener,this,dispatcher)
        controller.initialize();controller.unlock("1111".toCharArray());advanceUntilIdle()
        controller.createFolder("Trabajo");advanceUntilIdle();val folder=assertIs<RootState.Unlocked>(controller.state.value).browser.items.single{it.isDirectory}
        controller.enterFolder(folder.id);controller.importFile();advanceUntilIdle()
        var browser=assertIs<RootState.Unlocked>(controller.state.value).browser
        val imported=browser.items.single{!it.isDirectory};assertEquals("nuevo.txt",imported.name);assertIs<BrowserOperation.Idle>(browser.operation)
        controller.delete(imported.id);advanceUntilIdle();browser=assertIs<RootState.Unlocked>(controller.state.value).browser;assertTrue(browser.items.isEmpty())
        controller.changeCredential("3333".toCharArray(),"3333".toCharArray());advanceUntilIdle()
        assertIs<RootState.Locked>(controller.state.value);assertEquals("3333",service.primary.changedTo)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(service:FakeService)=LocalAppController(service,object:LocalFilePicker{override suspend fun pick():ImportSource?=null},NoopOpener,this,StandardTestDispatcher(testScheduler))
}

private object NoopOpener:VaultFileOpener{override suspend fun open(vault:VaultHandle,file:VaultItem.File)=Unit}
private class FakeService(var storage:LocalStorageState):LocalVaultService{
 val primary=FakeVault("primary.txt");val alternate=FakeVault("alternate.txt");var unlockCalls=0
 override suspend fun storageState()=storage
 override suspend fun createPair(primary:CharArray,alternate:CharArray){storage=LocalStorageState.READY}
 override suspend fun unlock(credential:CharArray):LocalUnlockResult{unlockCalls++;return when(credential.concatToString()){"1111"->LocalUnlockResult.Ready(primary);"2222"->LocalUnlockResult.Ready(alternate);else->LocalUnlockResult.InvalidCredential}}
}
private class FakeVault(initial:String):VaultHandle{
 private val entries=mutableListOf<VaultItem>(VaultItem.File(VaultItemId("seed"),null,initial,"text/plain",1,BlobId("blobseed")))
 override var isOpen=true;var changedTo:String?=null
 override suspend fun recover()=Unit
 override suspend fun reload()=Unit
 override fun items(parent:VaultDirectoryId?)=entries.filter{it.parentId==parent}
 override fun find(id:VaultItemId)=entries.firstOrNull{it.id==id}
 override suspend fun createDirectory(parent:VaultDirectoryId?,name:String)=VaultItem.Directory(VaultItemId("dir${entries.size}"),parent,name).also(entries::add)
 override suspend fun rename(id:VaultItemId,name:String){val i=entries.indexOfFirst{it.id==id};val old=entries[i];entries[i]=when(old){is VaultItem.File->old.copy(displayName=name);is VaultItem.Directory->old.copy(displayName=name)}}
 override suspend fun import(source:ImportSource,parent:VaultDirectoryId?,progress:suspend(ImportProgress)->Unit):VaultItem.File{progress(ImportProgress.Preparing);val e=VaultItem.File(VaultItemId("file${entries.size}"),parent,source.displayName,source.mimeHint,source.sizeHint?:0,BlobId("blob${entries.size}"));progress(ImportProgress.Encrypting(e.size,source.sizeHint));entries+=e;progress(ImportProgress.Complete(e));return e}
 override suspend fun deleteFile(id:VaultItemId){entries.removeAll{it.id==id}}
 override suspend fun deleteEmptyDirectory(id:VaultItemId){entries.removeAll{it.id==id}}
 override suspend fun readFile(id:VaultItemId,consume:suspend(ByteArray)->Unit)=0L
 override suspend fun changeCredential(newCredential:CharArray){changedTo=newCredential.concatToString()}
 override fun close(){isOpen=false}
}
private class BytesSource(override val displayName:String,private val bytes:ByteArray):ImportSource{override val mimeHint="text/plain";override val sizeHint=bytes.size.toLong();override suspend fun openRead()=object:ImportReadHandle{var done=false;override suspend fun read(maxBytes:Int)=if(done)ByteArray(0)else bytes.also{done=true};override suspend fun close()=Unit}}
