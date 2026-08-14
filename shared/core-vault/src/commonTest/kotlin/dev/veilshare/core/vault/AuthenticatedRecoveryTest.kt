package dev.veilshare.core.vault

import dev.veilshare.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AuthenticatedRecoveryTest {
 @Test fun `pre catalog commit deletes only journal proven orphan and is idempotent`()=runTest { val orphan=BlobId("orphan");val store=RecoveryBlobs(setOf(orphan));val journal=RecoveryJournal(JournalEntry(TransactionId("tx"),TransactionState.VERIFYING,setOf(orphan),"ns"));val r=VaultRecoveryCoordinator(journal,VaultGarbageCollector(store,journal));val s=AuthenticatedVaultState(VaultId("v"),"ns",emptySet());r.reconcileAuthenticated(s);assertFalse(store.exists(orphan));assertTrue(journal.entries().isEmpty());r.reconcileAuthenticated(s);assertFalse(store.exists(orphan)) }
 @Test fun `post catalog commit keeps referenced blob and clears stale journal`()=runTest { val live=BlobId("live");val store=RecoveryBlobs(setOf(live));val journal=RecoveryJournal(JournalEntry(TransactionId("tx"),TransactionState.VERIFYING,setOf(live),"ns"));val r=VaultRecoveryCoordinator(journal,VaultGarbageCollector(store,journal));r.reconcileAuthenticated(AuthenticatedVaultState(VaultId("v"),"ns",setOf(live)));assertTrue(store.exists(live));assertTrue(journal.entries().isEmpty()) }
 @Test fun `independent opaque namespaces cannot cross collect`()=runTest { val real=BlobId("realblob");val decoy=BlobId("decoyblob");val rstore=RecoveryBlobs(setOf(real));val dstore=RecoveryBlobs(setOf(decoy));val jr=RecoveryJournal();val jd=RecoveryJournal();VaultGarbageCollector(rstore,jr).collect(VaultGarbageCollector(rstore,jr).scan(AuthenticatedVaultState(VaultId("r"),"opaque-r",setOf(real))));assertTrue(dstore.exists(decoy));VaultGarbageCollector(dstore,jd).collect(VaultGarbageCollector(dstore,jd).scan(AuthenticatedVaultState(VaultId("d"),"opaque-d",setOf(decoy))));assertTrue(rstore.exists(real)) }
}
private class RecoveryJournal(vararg initial:JournalEntry):VaultJournal {private val all=initial.toMutableList();override suspend fun put(entry:JournalEntry){all.removeAll{it.id==entry.id};all+=entry};override suspend fun entries()=all.toList();override suspend fun remove(id:TransactionId){all.removeAll{it.id==id}}}
private class RecoveryBlobs(initial:Set<BlobId>):BlobStore {private val ids=initial.toMutableSet();override suspend fun create()=error("unused");override suspend fun open(id:BlobId)=error("unused");override suspend fun exists(id:BlobId)=id in ids;override suspend fun size(id:BlobId)=0L;override suspend fun delete(id:BlobId){ids-=id};override suspend fun listIds()=ids.toSet()}
