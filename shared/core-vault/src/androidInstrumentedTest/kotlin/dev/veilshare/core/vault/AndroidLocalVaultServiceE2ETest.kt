package dev.veilshare.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AndroidLocalVaultServiceE2ETest {
    @Test fun productionServiceSetupFolderImportReopenRotateDeleteAndIsolation() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=java.io.File(context.noBackupFilesDir,"ui-service-${System.nanoTime()}").also{assertTrue(it.mkdirs())}
        var service=AndroidLocalVaultService(root);assertEquals(LocalStorageState.EMPTY,service.storageState())
        service.createPair("4101".toCharArray(),"4202".toCharArray())
        val primary=assertIs<LocalUnlockResult.Ready>(service.unlock("4101".toCharArray())).vault
        val directory=primary.createDirectory(null,"Trabajo");val payload=ByteArray(1_100_000){((it*13)and 255).toByte()}
        val imported=primary.import(AndroidServiceSource("android.bin",payload),VaultDirectoryId(directory.id.value))
        val digest=java.security.MessageDigest.getInstance("SHA-256");primary.readFile(imported.id){digest.update(it)}
        assertContentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(payload),digest.digest());primary.close()

        service=AndroidLocalVaultService(root);val reopened=assertIs<LocalUnlockResult.Ready>(service.unlock("4101".toCharArray())).vault
        val file=assertIs<VaultItem.File>(reopened.items(VaultDirectoryId(directory.id.value)).single())
        reopened.changeCredential("4303".toCharArray());reopened.close()
        assertIs<LocalUnlockResult.InvalidCredential>(AndroidLocalVaultService(root).unlock("4101".toCharArray()))
        val rotated=assertIs<LocalUnlockResult.Ready>(AndroidLocalVaultService(root).unlock("4303".toCharArray())).vault
        rotated.deleteFile(file.id);assertTrue(rotated.items(VaultDirectoryId(directory.id.value)).isEmpty());rotated.close()
        val alternate=assertIs<LocalUnlockResult.Ready>(AndroidLocalVaultService(root).unlock("4202".toCharArray())).vault
        assertTrue(alternate.items(null).isEmpty());alternate.close()
    }
}

private class AndroidServiceSource(override val displayName:String,private val bytes:ByteArray):ImportSource{
    override val mimeHint="application/octet-stream";override val sizeHint=bytes.size.toLong()
    override suspend fun openRead()=object:ImportReadHandle{var offset=0;override suspend fun read(maxBytes:Int):ByteArray{if(offset==bytes.size)return ByteArray(0);val end=(offset+maxBytes).coerceAtMost(bytes.size);return bytes.copyOfRange(offset,end).also{offset=end}};override suspend fun close()=Unit}
}
