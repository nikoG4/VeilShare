package dev.veilshare.core.vault

import androidx.test.core.app.ApplicationProvider
import dev.veilshare.core.crypto.*
import dev.veilshare.core.model.BlobId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AndroidDeleteCrashMatrixE2ETest {
    @Test fun corruptSlotFailsClosedWhileSiblingRemainsUsable() = runTest {
        suspend fun run(corruptReal:Boolean, mutate:(ByteArray)->ByteArray) {
            val f=AndroidDeleteFixture.create();val realId=f.initial.session.descriptor.vaultId;f.initial.close();val decoy=AndroidDeleteFixture.open(f.root,f.policy,"310002");val decoyId=decoy.session.descriptor.vaultId;decoy.close()
            val slots=AndroidVaultSlotStore(f.root).all();val targetId=slots.single{it.second.vaultId==(if(corruptReal)realId else decoyId)}.first;val target=File(File(File(f.root,"bootstrap"),"slots"),"$targetId.vslot");val sibling=File(File(File(f.root,"bootstrap"),"slots"),"${slots.single{it.first!=targetId}.first}.vslot");val siblingBefore=sibling.readBytes();target.writeBytes(mutate(target.readBytes()))
            val badPin=if(corruptReal)"310001" else "310002";val goodPin=if(corruptReal)"310002" else "310001";val crypto=AndroidProductionCrypto.create();val bad=UnlockVaultUseCase(AndroidVaultSlotStore(f.root),crypto.passwordKdf,MatrixAndroidWrapper(crypto.cipher),crypto.cipher,f.policy).unlock(SensitiveChars(badPin.toCharArray()));assertIs<UnlockResult.InvalidCredential>(bad)
            val good=AndroidDeleteFixture.open(f.root,f.policy,goodPin);if(corruptReal){good.assertPlain(f.d,f.dPlain);assertContentEquals(f.dCipher,good.blobBytes(f.d.blobId))}else{good.assertPlain(f.a,f.aPlain);assertContentEquals(f.aCipher,good.blobBytes(f.a.blobId));good.assertPlain(f.b,f.bPlain)};good.close();assertContentEquals(siblingBefore,sibling.readBytes())
        }
        run(true){bytes->bytes.also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}}
        run(false){bytes->bytes.copyOf(bytes.size-1)}
        run(true){bytes->bytes+byteArrayOf(1)}
    }

    @Test fun physicalVbl1TruncationTrailingAndHeaderCorruptionAreIsolated() = runTest {
        val mutations=listOf<(ByteArray)->ByteArray>(
            {it.copyOf(3)}, {it.copyOf(it.size/2)}, {it.copyOf(it.size-1)},
            {it+byteArrayOf(1,2,3)}, {bytes->bytes.copyOf().also{it[0]=(it[0].toInt() xor 1).toByte()}},
        )
        mutations.forEach { mutate ->
            val f=AndroidDeleteFixture.create();f.initial.close();val path=File(File(File(f.root,"blobs"),f.realNamespace),"${f.a.blobId.value}.vblob");path.writeBytes(mutate(path.readBytes()))
            val real=AndroidDeleteFixture.open(f.root,f.policy,"310001");assertFails{real.assertPlain(f.a,f.aPlain)};assertTrue(real.snapshot.entries.any{it.id==f.a.id});assertContentEquals(f.bCipher,real.blobBytes(f.b.blobId));real.assertPlain(f.b,f.bPlain);real.recover();assertTrue(real.blobs.exists(f.a.blobId));assertContentEquals(f.bCipher,real.blobBytes(f.b.blobId));real.close();f.assertDecoy()
        }
    }

    @Test fun D0ThroughD8WithPhysicalRestartAndCiphertextCanaries() = runTest {
        AndroidDeleteFixture.create().also { it.initial.close(); it.assertPreserved() } // D0
        AndroidDeleteFixture.create().also { it.failAt(DeleteFaultPoint.IntentDurable); it.assertPreserved(true) } // D1
        listOf<(Int)->Int>({1},{it.coerceAtMost(9)},{it/2},{(it-2).coerceAtLeast(1)},{(it-1).coerceAtLeast(1)}).forEach { cut ->
            AndroidDeleteFixture.create().also { it.failCatalog(PartialAndroidOps(cut)); it.assertPreserved(true) }
        } // D2
        AndroidDeleteFixture.create().also { it.failCatalog(BeforeRenameAndroidOps); it.assertPreserved(true) } // D3
        AndroidDeleteFixture.create().also { it.failAt(DeleteFaultPoint.CatalogDurable); it.assertDeleted(true) } // D4
        AndroidDeleteFixture.create().also { it.failPhysicalDelete(); it.assertDeleted(true) } // D5
        AndroidDeleteFixture.create().also { it.failAt(DeleteFaultPoint.PhysicalDeleteComplete); it.assertDeleted(false) } // D6
        AndroidDeleteFixture.create().also { it.failJournalCleanup(); it.assertDeleted(false) } // D7
        AndroidDeleteFixture.create().also { it.failCatalog(AfterRenameAndroidOps); it.assertDeleted(true) } // D8
    }

    @Test fun syncFailureLeavesOldCatalogAuthoritative() = runTest {
        AndroidDeleteFixture.create().also { it.failCatalog(SyncFailureAndroidOps); it.assertPreserved(true) }
    }

    @Test fun failedPhysicalPinRotationKeepsOldSlotCatalogBlobsAndNamespace() = runTest {
        val f=AndroidDeleteFixture.create();val catalogFile=File(f.root,"catalogs").listFiles()!!.single{it.name.startsWith(f.initial.session.descriptor.vaultId.value.take(24))};val catalogBefore=catalogFile.readBytes()
        assertFailsWith<IOException>{ChangeCredentialUseCase(AndroidVaultSlotStore(f.root,BeforeRenameAndroidOps),f.initial.crypto.random,f.initial.crypto.passwordKdf,MatrixAndroidWrapper(f.initial.crypto.cipher),f.policy).change(f.initial.session,SensitiveChars("319999".toCharArray()))};f.initial.close()
        f.assertPreserved();assertContentEquals(catalogBefore,catalogFile.readBytes())
        val crypto=AndroidProductionCrypto.create();val failed=AndroidVaultRepository(f.root,UnlockVaultUseCase(AndroidVaultSlotStore(f.root),crypto.passwordKdf,MatrixAndroidWrapper(crypto.cipher),crypto.cipher,f.policy),AndroidProductionCrypto.keyDeriver(),crypto.cipher).open(SensitiveChars("319999".toCharArray()));assertIs<AndroidOpenResult.InvalidCredential>(failed)
    }

    @Test fun catalogAndJournalCorruptionFailClosedWithoutDeleteAuthority() = runTest {
        val catalogFixture=AndroidDeleteFixture.create();val realId=catalogFixture.initial.session.descriptor.vaultId;catalogFixture.initial.close();val catalog=File(catalogFixture.root,"catalogs").listFiles()!!.single{it.name.startsWith(realId.value.take(24))};val bytes=catalog.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();catalog.writeBytes(bytes)
        val crypto=AndroidProductionCrypto.create();val result=AndroidVaultRepository(catalogFixture.root,UnlockVaultUseCase(AndroidVaultSlotStore(catalogFixture.root),crypto.passwordKdf,MatrixAndroidWrapper(crypto.cipher),crypto.cipher,catalogFixture.policy),AndroidProductionCrypto.keyDeriver(),crypto.cipher).open(SensitiveChars("310001".toCharArray()));assertIs<AndroidOpenResult.Corrupt>(result);assertContentEquals(catalogFixture.aCipher,File(File(File(catalogFixture.root,"blobs"),catalogFixture.realNamespace),"${catalogFixture.a.blobId.value}.vblob").readBytes());catalogFixture.assertDecoy()

        val journalFixture=AndroidDeleteFixture.create();journalFixture.failAt(DeleteFaultPoint.IntentDurable);val journal=File(File(journalFixture.root,"state"),"j-9c4e0a.bin");val j=journal.readBytes();journal.writeBytes(j.copyOf(j.size-1));assertFailsWith<VaultFormatException>{AndroidVaultJournal(journalFixture.root)};assertContentEquals(journalFixture.aCipher,File(File(File(journalFixture.root,"blobs"),journalFixture.realNamespace),"${journalFixture.a.blobId.value}.vblob").readBytes());journalFixture.assertDecoyWithoutJournal()
    }

    @Test fun corruptOrMissingReferencedBlobDamagesOnlyTarget() = runTest {
        val corrupt=AndroidDeleteFixture.create();corrupt.initial.close();val aPath=File(File(File(corrupt.root,"blobs"),corrupt.realNamespace),"${corrupt.a.blobId.value}.vblob");val aBytes=aPath.readBytes();aBytes[aBytes.size/2]=(aBytes[aBytes.size/2].toInt() xor 1).toByte();aPath.writeBytes(aBytes);val opened=AndroidDeleteFixture.open(corrupt.root,corrupt.policy,"310001");assertFails{opened.assertPlain(corrupt.a,corrupt.aPlain)};assertContentEquals(corrupt.bCipher,opened.blobBytes(corrupt.b.blobId));opened.assertPlain(corrupt.b,corrupt.bPlain);opened.close();corrupt.assertDecoy()

        val missing=AndroidDeleteFixture.create();missing.initial.close();val missingPath=File(File(File(missing.root,"blobs"),missing.realNamespace),"${missing.a.blobId.value}.vblob");assertTrue(missingPath.delete());val reopened=AndroidDeleteFixture.open(missing.root,missing.policy,"310001");val state=AuthenticatedVaultState(reopened.session.descriptor.vaultId,reopened.session.descriptor.blobNamespace,reopened.snapshot.entries.filterIsInstance<VaultItem.File>().map{it.blobId}.toSet());assertEquals(VaultConsistency.Damaged,VaultConsistencyChecker(reopened.blobs,VaultGarbageCollector(reopened.blobs,reopened.journal)).check(state));assertContentEquals(missing.bCipher,reopened.blobBytes(missing.b.blobId));reopened.close();missing.assertDecoy()
    }
}

private class AndroidDeleteFixture(
    val root:File,val policy:Argon2Policy,val initial:AndroidMatrixRuntime,
    val a:VaultItem.File,val b:VaultItem.File,val d:VaultItem.File,
    val aPlain:ByteArray,val bPlain:ByteArray,val dPlain:ByteArray,
    val aCipher:ByteArray,val bCipher:ByteArray,val dCipher:ByteArray,
    val realNamespace:String,val decoyNamespace:String,
) {
    companion object {
        suspend fun create():AndroidDeleteFixture {
            val context=ApplicationProvider.getApplicationContext<android.content.Context>()
            val root=File(context.noBackupFilesDir,"matrix-${System.nanoTime()}").also{check(it.mkdirs())}
            val policy=Argon2Policy(Argon2Parameters(8192,1,1));val crypto=AndroidProductionCrypto.create()
            CreateVaultSetUseCase(AndroidVaultSlotStore(root),crypto.random,crypto.passwordKdf,MatrixAndroidWrapper(crypto.cipher),crypto.cipher,policy,AndroidVaultCatalogBootstrap(root,AndroidProductionCrypto.keyDeriver(),crypto.cipher)).create(SensitiveChars("310001".toCharArray()),SensitiveChars("310002".toCharArray()))
            val real=open(root,policy,"310001");val aPlain=ByteArray(8193){(it*3).toByte()};val bPlain=ByteArray(16391){(it*7+1).toByte()}
            val importer=ImportCoordinator(real.crypto.random,real.crypto.cipher,real.blobs,real.catalogStore,real.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),real.crypto.cipher))
            val (one,a)=importer.import(real.session,real.snapshot,MatrixBytesSource("A",aPlain));val (two,b)=importer.import(real.session,one,MatrixBytesSource("B",bPlain));real.snapshot=two
            val decoy=open(root,policy,"310002");val dPlain=ByteArray(4099){(it*11+2).toByte()};val (_,d)=ImportCoordinator(decoy.crypto.random,decoy.crypto.cipher,decoy.blobs,decoy.catalogStore,decoy.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),decoy.crypto.cipher)).import(decoy.session,decoy.snapshot,MatrixBytesSource("D",dPlain));val dCipher=decoy.blobBytes(d.blobId);val decoyNs=decoy.session.descriptor.blobNamespace;decoy.close()
            return AndroidDeleteFixture(root,policy,real,a,b,d,aPlain,bPlain,dPlain,real.blobBytes(a.blobId),real.blobBytes(b.blobId),dCipher,real.session.descriptor.blobNamespace,decoyNs)
        }
        suspend fun open(root:File,policy:Argon2Policy,pin:String):AndroidMatrixRuntime {
            val crypto=AndroidProductionCrypto.create();val opened=assertIs<AndroidOpenResult.Ready>(AndroidVaultRepository(root,UnlockVaultUseCase(AndroidVaultSlotStore(root),crypto.passwordKdf,MatrixAndroidWrapper(crypto.cipher),crypto.cipher,policy),AndroidProductionCrypto.keyDeriver(),crypto.cipher).open(SensitiveChars(pin.toCharArray())))
            return AndroidMatrixRuntime(crypto,opened.session,opened.catalog,AndroidEncryptedCatalogStore(root,opened.session.descriptor.vaultId,CatalogCrypto(AndroidProductionCrypto.keyDeriver(),crypto.cipher)),AndroidVaultJournal(root),AndroidBlobStore(File(root,"blobs"),opened.session.descriptor.blobNamespace))
        }
    }
    suspend fun failAt(point:DeleteFaultPoint){assertFailsWith<IllegalStateException>{DeleteFileUseCase(initial.crypto.random,initial.catalogStore,initial.journal,VaultGarbageCollector(initial.blobs,initial.journal)){if(it==point)throw IllegalStateException("fault")}.delete(initial.session,initial.snapshot,a.id)};initial.close()}
    suspend fun failCatalog(ops:AndroidPersistenceOps){val store=AndroidEncryptedCatalogStore(root,initial.session.descriptor.vaultId,CatalogCrypto(AndroidProductionCrypto.keyDeriver(),initial.crypto.cipher),persistence=ops);assertFailsWith<IOException>{DeleteFileUseCase(initial.crypto.random,store,initial.journal,VaultGarbageCollector(initial.blobs,initial.journal)).delete(initial.session,initial.snapshot,a.id)};initial.close()}
    suspend fun failPhysicalDelete(){val failing=FaultingAndroidBlobStore(initial.blobs);assertFailsWith<IOException>{DeleteFileUseCase(initial.crypto.random,initial.catalogStore,initial.journal,VaultGarbageCollector(failing,initial.journal)).delete(initial.session,initial.snapshot,a.id)};initial.close()}
    suspend fun failJournalCleanup(){val failing=FaultingAndroidJournal(initial.journal);assertFailsWith<IOException>{DeleteFileUseCase(initial.crypto.random,initial.catalogStore,failing,VaultGarbageCollector(initial.blobs,failing)).delete(initial.session,initial.snapshot,a.id)};initial.close()}
    suspend fun assertPreserved(expectJournal:Boolean=false){repeat(3){pass->val r=open(root,policy,"310001");if(pass==0&&expectJournal)assertTrue(r.journal.entries().isNotEmpty());assertTrue(r.snapshot.entries.any{it.id==a.id});assertContentEquals(aCipher,r.blobBytes(a.blobId));r.recover();assertContentEquals(aCipher,r.blobBytes(a.blobId));r.assertPlain(a,aPlain);assertB(r);r.close()};assertDecoy()}
    suspend fun assertDeleted(expectBlobBefore:Boolean){repeat(3){pass->val r=open(root,policy,"310001");assertFalse(r.snapshot.entries.any{it.id==a.id});if(pass==0){assertEquals(expectBlobBefore,r.blobs.exists(a.blobId));if(expectBlobBefore)assertContentEquals(aCipher,r.blobBytes(a.blobId))};r.recover();assertFalse(r.blobs.exists(a.blobId));assertB(r);assertTrue(r.journal.entries().isEmpty());r.close()};assertDecoy()}
    private suspend fun assertB(r:AndroidMatrixRuntime){assertEquals(realNamespace,r.session.descriptor.blobNamespace);assertContentEquals(bCipher,r.blobBytes(b.blobId));r.assertPlain(r.snapshot.entries.filterIsInstance<VaultItem.File>().single{it.id==b.id},bPlain)}
    suspend fun assertDecoy(){val r=open(root,policy,"310002");assertEquals(decoyNamespace,r.session.descriptor.blobNamespace);assertContentEquals(dCipher,r.blobBytes(d.blobId));r.assertPlain(r.snapshot.entries.filterIsInstance<VaultItem.File>().single{it.id==d.id},dPlain);r.close()}
    suspend fun assertDecoyWithoutJournal(){val crypto=AndroidProductionCrypto.create();val opened=assertIs<AndroidOpenResult.Ready>(AndroidVaultRepository(root,UnlockVaultUseCase(AndroidVaultSlotStore(root),crypto.passwordKdf,MatrixAndroidWrapper(crypto.cipher),crypto.cipher,policy),AndroidProductionCrypto.keyDeriver(),crypto.cipher).open(SensitiveChars("310002".toCharArray())));assertEquals(decoyNamespace,opened.session.descriptor.blobNamespace);val entry=opened.catalog.entries.filterIsInstance<VaultItem.File>().single{it.id==d.id};val blobs=AndroidBlobStore(File(root,"blobs"),decoyNamespace);val physical=blobs.open(d.blobId);val bytes=try{physical.readAt(0,physical.size.toInt())}finally{physical.close()};assertContentEquals(dCipher,bytes);val key=FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),crypto.cipher).unwrap(opened.session.key(),requireNotNull(entry.wrappedFileKey),opened.session.descriptor.vaultId,entry.id,entry.blobId);val handle=blobs.open(entry.blobId);val parts=mutableListOf<ByteArray>();try{EncryptedBlobReader(crypto.cipher).read(handle,key,entry.id.value.encodeToByteArray()){parts+=it}}finally{handle.close();key.material.close()};val plain=ByteArray(parts.sumOf{it.size});var offset=0;parts.forEach{it.copyInto(plain,offset);offset+=it.size};assertContentEquals(dPlain,plain);opened.session.close()}
}

private class AndroidMatrixRuntime(val crypto:ProductionCryptoComponents,val session:VaultSession,var snapshot:CatalogSnapshot,val catalogStore:AndroidEncryptedCatalogStore,val journal:AndroidVaultJournal,val blobs:AndroidBlobStore){
    suspend fun recover()=VaultRecoveryCoordinator(journal,VaultGarbageCollector(blobs,journal)).reconcileAuthenticated(AuthenticatedVaultState(session.descriptor.vaultId,session.descriptor.blobNamespace,snapshot.entries.filterIsInstance<VaultItem.File>().map{it.blobId}.toSet()))
    suspend fun blobBytes(id:BlobId):ByteArray{val h=blobs.open(id);return try{h.readAt(0,h.size.toInt())}finally{h.close()}}
    suspend fun assertPlain(file:VaultItem.File,expected:ByteArray){val key=FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),crypto.cipher).unwrap(session.key(),requireNotNull(file.wrappedFileKey),session.descriptor.vaultId,file.id,file.blobId);val h=blobs.open(file.blobId);val parts=mutableListOf<ByteArray>();try{EncryptedBlobReader(crypto.cipher).read(h,key,file.id.value.encodeToByteArray()){parts+=it}}finally{h.close();key.material.close()};val actual=ByteArray(parts.sumOf{it.size});var p=0;parts.forEach{it.copyInto(actual,p);p+=it.size};assertContentEquals(expected,actual)}
    fun close()=session.close()
}
private class MatrixBytesSource(override val displayName:String,private val bytes:ByteArray):ImportSource{override val mimeHint:String?=null;override val sizeHint:Long=bytes.size.toLong();override suspend fun openRead()=object:ImportReadHandle{var p=0;override suspend fun read(maxBytes:Int):ByteArray{if(p==bytes.size)return ByteArray(0);val e=minOf(bytes.size,p+maxBytes);return bytes.copyOfRange(p,e).also{p=e}};override suspend fun close()=Unit}}
private class MatrixAndroidWrapper(private val c:AuthenticatedCipher):KeyWrapper{override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=c.seal(kek.material,vaultKey.material.copy(),aad);override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(c.open(kek.material,wrapped,aad)))}
private class FaultingAndroidBlobStore(private val d:BlobStore):BlobStore by d{override suspend fun delete(id:BlobId):Nothing=throw IOException("injected delete failure")}
private class FaultingAndroidJournal(private val d:VaultJournal):VaultJournal by d{override suspend fun remove(id:TransactionId):Nothing=throw IOException("injected cleanup failure")}
private class PartialAndroidOps(private val cut:(Int)->Int):AndroidPersistenceOps{override fun writeAndSync(file:File,bytes:ByteArray){val n=cut(bytes.size).coerceIn(1,bytes.size-1);FileOutputStream(file,false).use{it.write(bytes,0,n);it.flush();it.fd.sync()};throw IOException("partial $n/${bytes.size}")};override fun sync(file:File)=AndroidDurableFiles.sync(file);override fun replace(temp:File,target:File)=error("replace after partial")}
private object SyncFailureAndroidOps:AndroidPersistenceOps{override fun writeAndSync(file:File,bytes:ByteArray){FileOutputStream(file,false).use{it.write(bytes);it.flush()};throw IOException("sync failure")};override fun sync(file:File)=AndroidDurableFiles.sync(file);override fun replace(temp:File,target:File)=error("replace after sync failure")}
private object BeforeRenameAndroidOps:AndroidPersistenceOps{override fun writeAndSync(file:File,bytes:ByteArray)=AndroidDurableFiles.writeAndSync(file,bytes);override fun sync(file:File)=AndroidDurableFiles.sync(file);override fun replace(temp:File,target:File):Nothing=throw IOException("before rename")}
private object AfterRenameAndroidOps:AndroidPersistenceOps{override fun writeAndSync(file:File,bytes:ByteArray)=AndroidDurableFiles.writeAndSync(file,bytes);override fun sync(file:File)=AndroidDurableFiles.sync(file);override fun replace(temp:File,target:File){AndroidDurableFiles.replace(temp,target);throw IOException("after rename")}}
