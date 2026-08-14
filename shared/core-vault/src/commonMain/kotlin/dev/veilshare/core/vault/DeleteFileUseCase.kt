package dev.veilshare.core.vault

import dev.veilshare.core.crypto.SecureRandom
enum class DeleteFaultPoint { IntentDurable, CatalogDurable, BeforePhysicalDelete, PhysicalDeleteComplete, BeforeJournalCleanup }

/** Logical commit precedes physical cleanup; a crash leaves an encrypted, journal-proven orphan. */
class DeleteFileUseCase(private val random:SecureRandom, private val catalog:EncryptedCatalogStore, private val journal:VaultJournal, private val garbage:VaultGarbageCollector, private val fault:suspend(DeleteFaultPoint)->Unit={}) {
 suspend fun delete(session:VaultSession,snapshot:CatalogSnapshot,id:VaultItemId):CatalogSnapshot { val file=snapshot.entries.filterIsInstance<VaultItem.File>().firstOrNull{it.id==id}?:throw VaultFormatException("File not found");val tx=TransactionId(random.bytes(16).joinToString(""){it.toUByte().toString(16).padStart(2,'0')});journal.put(JournalEntry(tx,TransactionState.VERIFYING,setOf(file.blobId),session.descriptor.blobNamespace));fault(DeleteFaultPoint.IntentDurable);val next=snapshot.copy(entries=snapshot.entries.filter{it.id!=id});catalog.replaceAtomically(session.key(),next);fault(DeleteFaultPoint.CatalogDurable);val state=AuthenticatedVaultState(session.descriptor.vaultId,session.descriptor.blobNamespace,next.entries.filterIsInstance<VaultItem.File>().map{it.blobId}.toSet());fault(DeleteFaultPoint.BeforePhysicalDelete);garbage.collect(garbage.scan(state));fault(DeleteFaultPoint.PhysicalDeleteComplete);fault(DeleteFaultPoint.BeforeJournalCleanup);journal.remove(tx);return next }
}
