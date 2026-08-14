package dev.veilshare.core.vault

import dev.veilshare.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VaultConsistencyCheckerTest { @Test fun `missing reference is damaged and confirmed orphan needs cleanup`()=runTest { val live=BlobId("live");val orphan=BlobId("orphan");val journal=ConsistencyJournal(JournalEntry(TransactionId("t"),TransactionState.VERIFYING,setOf(orphan),"n"));val store=ConsistencyBlobs(setOf(live,orphan));val gc=VaultGarbageCollector(store,journal);val checker=VaultConsistencyChecker(store,gc);val state=AuthenticatedVaultState(VaultId("v"),"n",setOf(live));assertEquals(VaultConsistency.NeedsCleanup,checker.check(state));gc.collect(gc.scan(state));assertEquals(VaultConsistency.Healthy,checker.check(state));store.delete(live);assertEquals(VaultConsistency.Damaged,checker.check(state)) } }
private class ConsistencyJournal(vararg x:JournalEntry):VaultJournal {private val e=x.toMutableList();override suspend fun put(entry:JournalEntry){e+=entry};override suspend fun entries()=e.toList();override suspend fun remove(id:TransactionId){e.removeAll{it.id==id}}}
private class ConsistencyBlobs(x:Set<BlobId>):BlobStore {private val b=x.toMutableSet();override suspend fun create()=error("unused");override suspend fun open(id:BlobId)=error("unused");override suspend fun exists(id:BlobId)=id in b;override suspend fun size(id:BlobId)=0L;override suspend fun delete(id:BlobId){b-=id};override suspend fun listIds()=b.toSet()}
