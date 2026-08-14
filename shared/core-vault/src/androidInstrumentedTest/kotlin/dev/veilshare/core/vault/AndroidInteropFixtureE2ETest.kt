package dev.veilshare.core.vault

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.veilshare.core.crypto.*
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AndroidInteropFixtureE2ETest {
    @Test fun desktopProducedTreeOpensAndAndroidMutationIsExportedByteForByte() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.noBackupFilesDir,"interop-desktop-${System.nanoTime()}").also{assertTrue(it.mkdirs())}
        val manifest=context.assets.open("desktop-produced-vault.zip").use{unpackFixture(it,root)};verifyAndroidArtifactHashes(root,manifest)
        val before=physicalHashes(root)
        val real=openAndroidInterop(root,manifest.getProperty("real.pin"));assertEquals(manifest.getProperty("real.namespace"),real.session.descriptor.blobNamespace);verifyAndroidEntries(real,manifest,listOf("DESKTOP_A.txt","DESKTOP_B.bin"))
        val cBytes="ANDROID_MUTATION_CONTENT_743".encodeToByteArray();val (next,_)=ImportCoordinator(real.crypto.random,real.crypto.cipher,real.blobs,real.catalogStore,real.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),real.crypto.cipher)).import(real.session,real.snapshot,InteropAndroidSource("ANDROID_C.txt",cBytes));real.snapshot=next;manifest.setProperty("ANDROID_C.txt.sha256",sha256Android(cBytes));real.close()
        val decoy=openAndroidInterop(root,manifest.getProperty("decoy.pin"));assertEquals(manifest.getProperty("decoy.namespace"),decoy.session.descriptor.blobNamespace);verifyAndroidEntries(decoy,manifest,listOf("DESKTOP_D.txt"));decoy.close()
        before.forEach{(path,hash)->if(!path.contains("catalogs/"))assertEquals(hash,physicalHashes(root)[path],"Android unexpectedly changed $path")}
        recordAndroidArtifactHashes(root,manifest);if(exportRequested())exportFixture(context,root,manifest,"android-mutated-desktop-vault.zip")
    }

    @Test fun androidProductionTreeIsExportedForDesktopConsumer() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.noBackupFilesDir,"interop-android-${System.nanoTime()}").also{assertTrue(it.mkdirs())};val policy=Argon2Policy(Argon2Parameters(8192,1,1));val crypto=AndroidProductionCrypto.create()
        CreateVaultSetUseCase(AndroidVaultSlotStore(root),crypto.random,crypto.passwordKdf,InteropAndroidWrapper(crypto.cipher),crypto.cipher,policy,AndroidVaultCatalogBootstrap(root,AndroidProductionCrypto.keyDeriver(),crypto.cipher)).create(SensitiveChars("510001".toCharArray()),SensitiveChars("510002".toCharArray()))
        val real=openAndroidInterop(root,"510001");val a="ANDROID_A_CONTENT_744".encodeToByteArray();val b=ByteArray(VeilCryptoSuites.v1.chunkBytes+53){(it*23+5).toByte()};val importer=ImportCoordinator(real.crypto.random,real.crypto.cipher,real.blobs,real.catalogStore,real.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),real.crypto.cipher));val(one,_)=importer.import(real.session,real.snapshot,InteropAndroidSource("ANDROID_A.txt",a));val(two,_)=importer.import(real.session,one,InteropAndroidSource("ANDROID_B.bin",b));real.snapshot=two;val realNs=real.session.descriptor.blobNamespace;real.close()
        val decoy=openAndroidInterop(root,"510002");val d="ANDROID_DECOY_CONTENT_745".encodeToByteArray();ImportCoordinator(decoy.crypto.random,decoy.crypto.cipher,decoy.blobs,decoy.catalogStore,decoy.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),decoy.crypto.cipher)).import(decoy.session,decoy.snapshot,InteropAndroidSource("ANDROID_D.txt",d));val decoyNs=decoy.session.descriptor.blobNamespace;decoy.close()
        val manifest=Properties().apply{setProperty("producer","android");setProperty("real.pin","510001");setProperty("decoy.pin","510002");setProperty("real.namespace",realNs);setProperty("decoy.namespace",decoyNs);setProperty("ANDROID_A.txt.sha256",sha256Android(a));setProperty("ANDROID_B.bin.sha256",sha256Android(b));setProperty("ANDROID_D.txt.sha256",sha256Android(d))}
        recordAndroidArtifactHashes(root,manifest);if(exportRequested())exportFixture(context,root,manifest,"android-produced-vault.zip")
    }
}

private suspend fun openAndroidInterop(root:File,pin:String):AndroidInteropRuntime{val crypto=AndroidProductionCrypto.create();val policy=Argon2Policy(Argon2Parameters(8192,1,1));val opened=assertIs<AndroidOpenResult.Ready>(AndroidVaultRepository(root,UnlockVaultUseCase(AndroidVaultSlotStore(root),crypto.passwordKdf,InteropAndroidWrapper(crypto.cipher),crypto.cipher,policy),AndroidProductionCrypto.keyDeriver(),crypto.cipher).open(SensitiveChars(pin.toCharArray())));return AndroidInteropRuntime(crypto,opened.session,opened.catalog,AndroidEncryptedCatalogStore(root,opened.session.descriptor.vaultId,CatalogCrypto(AndroidProductionCrypto.keyDeriver(),crypto.cipher)),AndroidVaultJournal(root),AndroidBlobStore(File(root,"blobs"),opened.session.descriptor.blobNamespace))}
private data class AndroidInteropRuntime(val crypto:ProductionCryptoComponents,val session:VaultSession,var snapshot:CatalogSnapshot,val catalogStore:AndroidEncryptedCatalogStore,val journal:AndroidVaultJournal,val blobs:AndroidBlobStore){suspend fun digest(file:VaultItem.File):String{val digest=MessageDigest.getInstance("SHA-256");val key=FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),crypto.cipher).unwrap(session.key(),requireNotNull(file.wrappedFileKey),session.descriptor.vaultId,file.id,file.blobId);val handle=blobs.open(file.blobId);try{EncryptedBlobReader(crypto.cipher).read(handle,key,file.id.value.encodeToByteArray()){digest.update(it)}}finally{handle.close();key.material.close()};return digest.digest().toHexAndroid()};fun close()=session.close()}
private suspend fun verifyAndroidEntries(runtime:AndroidInteropRuntime,manifest:Properties,names:List<String>){names.forEach{name->val file=runtime.snapshot.entries.filterIsInstance<VaultItem.File>().single{it.displayName==name};assertEquals(manifest.getProperty("$name.sha256"),runtime.digest(file))}}
private class InteropAndroidSource(override val displayName:String,private val bytes:ByteArray):ImportSource{override val mimeHint:String?="application/octet-stream";override val sizeHint=bytes.size.toLong();override suspend fun openRead()=object:ImportReadHandle{var offset=0;override suspend fun read(maxBytes:Int):ByteArray{if(offset>=bytes.size)return ByteArray(0);val end=minOf(bytes.size,offset+maxBytes);return bytes.copyOfRange(offset,end).also{offset=end}};override suspend fun close()=Unit}}
private class InteropAndroidWrapper(private val cipher:AuthenticatedCipher):KeyWrapper{override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=cipher.seal(kek.material,vaultKey.material.copy(),aad);override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(cipher.open(kek.material,wrapped,aad)))}

private fun unpackFixture(input:java.io.InputStream,target:File):Properties{val props=Properties();ZipInputStream(input).use{zip->while(true){val entry=zip.nextEntry?:break;if(entry.name=="manifest.properties")props.load(zip)else if(entry.name.startsWith("root/")&&!entry.isDirectory){val relative=entry.name.removePrefix("root/");require(!relative.contains(".."));val out=File(target,relative).canonicalFile;require(out.path.startsWith(target.canonicalPath+File.separator));out.parentFile!!.mkdirs();out.outputStream().use{zip.copyTo(it)}};zip.closeEntry()}};return props}
private fun exportFixture(context:Context,root:File,manifest:Properties,name:String){val resolver=context.contentResolver;resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,"${MediaStore.MediaColumns.DISPLAY_NAME}=?",arrayOf(name));val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/zip");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/");put(MediaStore.MediaColumns.IS_PENDING,1)};val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values));resolver.openOutputStream(uri,"w")!!.use{raw->ZipOutputStream(raw).use{zip->val manifestBytes=java.io.ByteArrayOutputStream().also{manifest.store(it,"VeilShare synthetic cross-platform fixture")}.toByteArray();zip.putNextEntry(ZipEntry("manifest.properties"));zip.write(manifestBytes);zip.closeEntry();root.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(root).invariantSeparatorsPath}.forEach{file->zip.putNextEntry(ZipEntry("root/${file.relativeTo(root).invariantSeparatorsPath}"));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}};values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);resolver.update(uri,values,null,null)}
private fun physicalHashes(root:File)=root.walkTopDown().filter{it.isFile}.associate{it.relativeTo(root).invariantSeparatorsPath to sha256Android(it.readBytes())}
private fun sha256Android(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).toHexAndroid()
private fun ByteArray.toHexAndroid()=joinToString(""){it.toUByte().toString(16).padStart(2,'0')}
private fun exportRequested()=InstrumentationRegistry.getArguments().getString("exportInterop")=="true"
private fun recordAndroidArtifactHashes(root:File,properties:Properties){properties.stringPropertyNames().filter{it.startsWith("artifact.")}.forEach(properties::remove);root.walkTopDown().filter{it.isFile}.forEach{file->properties.setProperty("artifact.${file.relativeTo(root).invariantSeparatorsPath}.sha256",sha256Android(file.readBytes()))}}
private fun verifyAndroidArtifactHashes(root:File,properties:Properties){properties.stringPropertyNames().filter{it.startsWith("artifact.")&&it.endsWith(".sha256")}.forEach{key->val relative=key.removePrefix("artifact.").removeSuffix(".sha256");assertEquals(properties.getProperty(key),sha256Android(File(root,relative).readBytes()),relative)}}
