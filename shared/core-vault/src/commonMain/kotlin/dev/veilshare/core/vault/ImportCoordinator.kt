package dev.veilshare.core.vault

import dev.veilshare.core.crypto.*
import dev.veilshare.core.model.BlobId

interface ImportReadHandle { suspend fun read(maxBytes:Int):ByteArray; suspend fun close() }
interface ImportSource { val displayName:String; val mimeHint:String?; val sizeHint:Long?; suspend fun openRead():ImportReadHandle }
sealed interface ImportProgress { data object Preparing:ImportProgress; data class Encrypting(val bytes:Long,val total:Long?):ImportProgress; data object Committing:ImportProgress; data class Complete(val item:VaultItem.File):ImportProgress }
/** Test seam; production callers use the default no-op. CatalogDurable is the no-rollback commit point. */
enum class ImportFaultPoint { BlobDurableBeforeCatalog, CatalogDurable }
/** Commit order: blob first, then encrypted catalog; an interrupted import can leave only an orphan blob. */
class ImportCoordinator(private val random:SecureRandom, private val cipher:AuthenticatedCipher, private val blobs:BlobStore, private val catalog:EncryptedCatalogStore, private val journal:VaultJournal, private val wrapping:FileKeyWrapping, private val fault:suspend(ImportFaultPoint)->Unit={}) {
    suspend fun import(session:VaultSession, snapshot:CatalogSnapshot, source:ImportSource, parent:VaultDirectoryId?=null, progress:suspend(ImportProgress)->Unit={}): Pair<CatalogSnapshot,VaultItem.File> {
        check(session.isOpen); progress(ImportProgress.Preparing); val itemId=VaultItemId(random.bytes(16).hex()); val tx=TransactionId(random.bytes(16).hex()); val context=itemId.value.encodeToByteArray()
        journal.put(JournalEntry(tx,TransactionState.PREPARING,emptySet(),session.descriptor.blobNamespace)); val fileKey=FileKeyGenerator(random).generate(); val input=source.openRead()
        try { val result=EncryptedBlobWriter(cipher,random).write({ n -> input.read(n) },blobs.create(),fileKey,context); journal.put(JournalEntry(tx,TransactionState.VERIFYING,setOf(result.blobId),session.descriptor.blobNamespace)); fault(ImportFaultPoint.BlobDurableBeforeCatalog); progress(ImportProgress.Encrypting(result.size,source.sizeHint)); val wrapped=wrapping.wrap(session.key(),fileKey,session.descriptor.vaultId,itemId,result.blobId); val entry=VaultItem.File(itemId,parent,source.displayName,source.mimeHint,result.size,result.blobId,wrappedFileKey=wrapped); val next=snapshot.copy(entries=snapshot.entries+entry); progress(ImportProgress.Committing); catalog.replaceAtomically(session.key(),next); fault(ImportFaultPoint.CatalogDurable); journal.put(JournalEntry(tx,TransactionState.COMMITTED,setOf(result.blobId),session.descriptor.blobNamespace)); journal.remove(tx); progress(ImportProgress.Complete(entry)); return next to entry } finally { input.close(); fileKey.material.close() }
    }
}
private fun ByteArray.hex()=joinToString(""){it.toUByte().toString(16).padStart(2,'0')}
