package dev.veilshare.core.vault

import androidx.test.core.app.ApplicationProvider
import dev.veilshare.core.crypto.AndroidProductionCrypto
import dev.veilshare.core.crypto.Argon2Parameters
import dev.veilshare.core.crypto.Argon2Policy
import dev.veilshare.core.crypto.AuthenticatedCipher
import dev.veilshare.core.crypto.KeyEncryptionKey
import dev.veilshare.core.crypto.KeyWrapper
import dev.veilshare.core.crypto.SealedBytes
import dev.veilshare.core.crypto.SensitiveBytes
import dev.veilshare.core.crypto.SensitiveChars
import dev.veilshare.core.crypto.VaultKey
import dev.veilshare.core.crypto.VeilCryptoSuites
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidVaultE2ETest {
    @Test fun sparseBlobLargerThanTwoGiBStillUsesBoundedReads() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "large-${System.nanoTime()}").also { assertTrue(it.mkdirs()) }
        val namespace = "abcdef0123456789abcdef0123456789"
        val id = dev.veilshare.core.model.BlobId("0123456789abcdef0123456789abcdef")
        val namespaceRoot = File(root, namespace).also { assertTrue(it.mkdirs()) }
        val blob = File(namespaceRoot, "${id.value}.vblob")
        RandomAccessFile(blob, "rw").use { file ->
            file.setLength(Int.MAX_VALUE.toLong() + 4096L)
            file.seek(0)
            file.write(byteArrayOf(1, 2, 3, 4))
        }
        val handle = AndroidBlobStore(root, namespace).open(id)
        try { assertContentEquals(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0), handle.readAt(0, 8)) }
        finally { handle.close() }
    }

    @Test fun physicalVaultRootDoesNotExposeLogicalMetadataOrPlaintext() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "canary-${System.nanoTime()}").also { assertTrue(it.mkdirs()) }
        val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))
        val crypto = AndroidProductionCrypto.create()
        CreateVaultSetUseCase(
            AndroidVaultSlotStore(root), crypto.random, crypto.passwordKdf,
            AndroidKeyWrapper(crypto.cipher), crypto.cipher, policy,
            AndroidVaultCatalogBootstrap(root, AndroidProductionCrypto.keyDeriver(), crypto.cipher),
        ).create(SensitiveChars("410001".toCharArray()), SensitiveChars("410002".toCharArray()))

        val secretName = "ANDROID_SECRET_FILENAME_593821.txt"
        val secretContent = "ANDROID_SECRET_CONTENT_194720".encodeToByteArray()
        val secretMime = "application/x-veil-android-canary"
        val real = open(root, policy, "410001")
        val realNamespace = real.session.descriptor.blobNamespace
        ImportCoordinator(
            real.crypto.random, real.crypto.cipher, real.blobs, real.catalog, real.journal,
            FileKeyWrapping(AndroidProductionCrypto.keyDeriver(), real.crypto.cipher),
        ).import(real.session, real.snapshot, AndroidCanarySource(secretName, secretMime, secretContent))
        real.close()

        val decoy = open(root, policy, "410002")
        val decoyNamespace = decoy.session.descriptor.blobNamespace
        assertFalse(realNamespace == decoyNamespace)
        decoy.close()

        val reopened = open(root, policy, "410001")
        assertEquals(realNamespace, reopened.session.descriptor.blobNamespace)
        reopened.assertPlain(reopened.snapshot.entries.filterIsInstance<VaultItem.File>().single(), secretContent)
        reopened.close()

        val needles = listOf(secretName.encodeToByteArray(), secretContent, secretMime.encodeToByteArray())
        val files = root.walkTopDown().filter { it.isFile }.toList()
        needles.forEach { needle -> assertEquals(0, files.count { it.readBytes().containsSubsequence(needle) }) }
        val physicalNames = root.walkTopDown().map { it.name.lowercase() }.toList()
        assertTrue(physicalNames.none { "real" in it || "decoy" in it })
    }

    @Test fun createImportReopenDecryptAndPostCommitDeleteRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "test-${System.nanoTime()}").also { assertTrue(it.mkdirs()) }
        val policy = Argon2Policy(Argon2Parameters(8192, 1, 1))
        val initialCrypto = AndroidProductionCrypto.create()
        CreateVaultSetUseCase(
            AndroidVaultSlotStore(root), initialCrypto.random, initialCrypto.passwordKdf,
            AndroidKeyWrapper(initialCrypto.cipher), initialCrypto.cipher, policy,
            AndroidVaultCatalogBootstrap(root, AndroidProductionCrypto.keyDeriver(), initialCrypto.cipher),
        ).create(SensitiveChars("000123".toCharArray()), SensitiveChars("000124".toCharArray()))

        val real = open(root, policy, "000123")
        val decoy = open(root, policy, "000124")
        assertFalse(real.session.descriptor.blobNamespace == decoy.session.descriptor.blobNamespace)
        val decoyNamespace = decoy.session.descriptor.blobNamespace
        assertTrue(decoy.snapshot.entries.isEmpty())
        decoy.close()

        val payload = ByteArray(VeilCryptoSuites.v1.chunkBytes + 73) { ((it * 19 + 7) and 0xff).toByte() }
        val (_, imported) = ImportCoordinator(
            real.crypto.random, real.crypto.cipher, real.blobs, real.catalog, real.journal,
            FileKeyWrapping(AndroidProductionCrypto.keyDeriver(), real.crypto.cipher),
        ).import(real.session, real.snapshot, AndroidBytesSource("opaque.txt", payload))
        real.close()

        val reopened = open(root, policy, "000123")
        val durable = reopened.snapshot.entries.filterIsInstance<VaultItem.File>().single()
        assertEquals(imported.id, durable.id)
        reopened.assertPlain(durable, payload)

        assertFailsWith<IllegalStateException> {
            DeleteFileUseCase(
                reopened.crypto.random, reopened.catalog, reopened.journal,
                VaultGarbageCollector(reopened.blobs, reopened.journal),
            ) { if (it == DeleteFaultPoint.CatalogDurable) throw IllegalStateException("crash") }
                .delete(reopened.session, reopened.snapshot, durable.id)
        }
        assertTrue(reopened.blobs.exists(durable.blobId))
        reopened.close()

        val afterCrash = open(root, policy, "000123")
        assertTrue(afterCrash.snapshot.entries.isEmpty())
        val state = AuthenticatedVaultState(afterCrash.session.descriptor.vaultId, afterCrash.session.descriptor.blobNamespace, emptySet())
        VaultRecoveryCoordinator(afterCrash.journal, VaultGarbageCollector(afterCrash.blobs, afterCrash.journal)).reconcileAuthenticated(state)
        assertFalse(afterCrash.blobs.exists(durable.blobId))
        assertTrue(afterCrash.journal.entries().isEmpty())
        afterCrash.close()

        val stable = open(root, policy, "000123")
        VaultRecoveryCoordinator(stable.journal, VaultGarbageCollector(stable.blobs, stable.journal)).reconcileAuthenticated(
            AuthenticatedVaultState(stable.session.descriptor.vaultId, stable.session.descriptor.blobNamespace, emptySet()),
        )
        assertTrue(stable.snapshot.entries.isEmpty())
        stable.close()
        val decoyAfterRealRecovery=open(root,policy,"000124");assertEquals(decoyNamespace,decoyAfterRealRecovery.session.descriptor.blobNamespace);assertTrue(decoyAfterRealRecovery.snapshot.entries.isEmpty());decoyAfterRealRecovery.close()
    }

    @Test fun cancellationBeforeAndAfterCatalogCommitPreservesAndroidSemantics() = runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val root=File(context.noBackupFilesDir,"cancel-${System.nanoTime()}").also{assertTrue(it.mkdirs())}
        val policy=Argon2Policy(Argon2Parameters(8192,1,1)); val crypto=AndroidProductionCrypto.create()
        CreateVaultSetUseCase(AndroidVaultSlotStore(root),crypto.random,crypto.passwordKdf,AndroidKeyWrapper(crypto.cipher),crypto.cipher,policy,AndroidVaultCatalogBootstrap(root,AndroidProductionCrypto.keyDeriver(),crypto.cipher)).create(SensitiveChars("100001".toCharArray()),SensitiveChars("100002".toCharArray()))

        val before=open(root,policy,"100001"); val payload=ByteArray(128*1024){it.toByte()}
        val pre=ImportCoordinator(before.crypto.random,before.crypto.cipher,before.blobs,before.catalog,before.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),before.crypto.cipher)){if(it==ImportFaultPoint.BlobDurableBeforeCatalog)throw CancellationException("pre")}
        assertFailsWith<CancellationException>{pre.import(before.session,before.snapshot,AndroidBytesSource("pre.bin",payload))};before.close()
        val afterPre=open(root,policy,"100001");val emptyState=AuthenticatedVaultState(afterPre.session.descriptor.vaultId,afterPre.session.descriptor.blobNamespace,emptySet());VaultRecoveryCoordinator(afterPre.journal,VaultGarbageCollector(afterPre.blobs,afterPre.journal)).reconcileAuthenticated(emptyState);assertTrue(afterPre.snapshot.entries.isEmpty());assertTrue(afterPre.blobs.listIds().isEmpty())

        val post=ImportCoordinator(afterPre.crypto.random,afterPre.crypto.cipher,afterPre.blobs,afterPre.catalog,afterPre.journal,FileKeyWrapping(AndroidProductionCrypto.keyDeriver(),afterPre.crypto.cipher)){if(it==ImportFaultPoint.CatalogDurable)throw CancellationException("post")}
        assertFailsWith<CancellationException>{post.import(afterPre.session,afterPre.snapshot,AndroidBytesSource("post.bin",payload))};afterPre.close()
        val afterPost=open(root,policy,"100001");val entry=afterPost.snapshot.entries.filterIsInstance<VaultItem.File>().single();afterPost.assertPlain(entry,payload);VaultRecoveryCoordinator(afterPost.journal,VaultGarbageCollector(afterPost.blobs,afterPost.journal)).reconcileAuthenticated(AuthenticatedVaultState(afterPost.session.descriptor.vaultId,afterPost.session.descriptor.blobNamespace,setOf(entry.blobId)));assertTrue(afterPost.blobs.exists(entry.blobId));afterPost.close()
    }

    @Test fun AndroidPinRotationKeepsNamespaceAndOtherVault() = runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val root=File(context.noBackupFilesDir,"pin-${System.nanoTime()}").also{assertTrue(it.mkdirs())}
        val policy=Argon2Policy(Argon2Parameters(8192,1,1));val crypto=AndroidProductionCrypto.create();val wrapper=AndroidKeyWrapper(crypto.cipher)
        CreateVaultSetUseCase(AndroidVaultSlotStore(root),crypto.random,crypto.passwordKdf,wrapper,crypto.cipher,policy,AndroidVaultCatalogBootstrap(root,AndroidProductionCrypto.keyDeriver(),crypto.cipher)).create(SensitiveChars("200001".toCharArray()),SensitiveChars("200002".toCharArray()))
        val real=open(root,policy,"200001");val namespace=real.session.descriptor.blobNamespace;ChangeCredentialUseCase(AndroidVaultSlotStore(root),real.crypto.random,real.crypto.passwordKdf,AndroidKeyWrapper(real.crypto.cipher),policy).change(real.session,SensitiveChars("299999".toCharArray()));real.close()
        val oldCrypto=AndroidProductionCrypto.create();assertIs<UnlockResult.InvalidCredential>(UnlockVaultUseCase(AndroidVaultSlotStore(root),oldCrypto.passwordKdf,AndroidKeyWrapper(oldCrypto.cipher),oldCrypto.cipher,policy).unlock(SensitiveChars("200001".toCharArray())))
        val rotated=open(root,policy,"299999");assertEquals(namespace,rotated.session.descriptor.blobNamespace);rotated.close();val decoy=open(root,policy,"200002");assertTrue(decoy.snapshot.entries.isEmpty());decoy.close()
    }

    private suspend fun open(root: File, policy: Argon2Policy, pin: String): AndroidRuntime {
        val crypto = AndroidProductionCrypto.create()
        val result = assertIs<AndroidOpenResult.Ready>(
            AndroidVaultRepository(
                root,
                UnlockVaultUseCase(AndroidVaultSlotStore(root), crypto.passwordKdf, AndroidKeyWrapper(crypto.cipher), crypto.cipher, policy),
                AndroidProductionCrypto.keyDeriver(), crypto.cipher,
            ).open(SensitiveChars(pin.toCharArray())),
        )
        return AndroidRuntime(
            crypto, result.session, result.catalog,
            AndroidEncryptedCatalogStore(root, result.session.descriptor.vaultId, CatalogCrypto(AndroidProductionCrypto.keyDeriver(), crypto.cipher)),
            AndroidVaultJournal(root), AndroidBlobStore(File(root, "blobs"), result.session.descriptor.blobNamespace),
        )
    }
}

private class AndroidCanarySource(
    override val displayName: String,
    override val mimeHint: String,
    private val bytes: ByteArray,
) : ImportSource {
    override val sizeHint: Long = bytes.size.toLong()
    override suspend fun openRead(): ImportReadHandle = object : ImportReadHandle {
        private var offset = 0
        override suspend fun read(maxBytes: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            val end = minOf(bytes.size, offset + maxBytes)
            return bytes.copyOfRange(offset, end).also { offset = end }
        }
        override suspend fun close() = Unit
    }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matches = true
        for (index in needle.indices) {
            if (this[start + index] != needle[index]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

private data class AndroidRuntime(
    val crypto: dev.veilshare.core.crypto.ProductionCryptoComponents,
    val session: VaultSession,
    val snapshot: CatalogSnapshot,
    val catalog: AndroidEncryptedCatalogStore,
    val journal: AndroidVaultJournal,
    val blobs: AndroidBlobStore,
) {
    suspend fun assertPlain(file: VaultItem.File, expected: ByteArray) {
        val key = FileKeyWrapping(AndroidProductionCrypto.keyDeriver(), crypto.cipher).unwrap(
            session.key(), requireNotNull(file.wrappedFileKey), session.descriptor.vaultId, file.id, file.blobId,
        )
        val source=blobs.open(file.blobId); val chunks=mutableListOf<ByteArray>()
        try { EncryptedBlobReader(crypto.cipher).read(source,key,file.id.value.encodeToByteArray()){chunks+=it} }
        finally { source.close(); key.material.close() }
        val actual=ByteArray(chunks.sumOf { it.size }); var offset=0
        chunks.forEach { it.copyInto(actual,offset); offset+=it.size }
        assertContentEquals(expected,actual)
    }
    fun close()=session.close()
}

private class AndroidBytesSource(override val displayName:String, private val bytes:ByteArray):ImportSource {
    override val mimeHint:String?="application/octet-stream"; override val sizeHint:Long=bytes.size.toLong()
    override suspend fun openRead():ImportReadHandle = object:ImportReadHandle { var offset=0
        override suspend fun read(maxBytes:Int):ByteArray { if(offset>=bytes.size)return ByteArray(0);val end=minOf(bytes.size,offset+maxBytes);return bytes.copyOfRange(offset,end).also{offset=end} }
        override suspend fun close()=Unit
    }
}

private class AndroidKeyWrapper(private val cipher:AuthenticatedCipher):KeyWrapper {
    override suspend fun wrap(kek:KeyEncryptionKey,vaultKey:VaultKey,aad:ByteArray)=cipher.seal(kek.material,vaultKey.material.copy(),aad)
    override suspend fun unwrap(kek:KeyEncryptionKey,wrapped:SealedBytes,aad:ByteArray)=VaultKey(SensitiveBytes(cipher.open(kek.material,wrapped,aad)))
}
