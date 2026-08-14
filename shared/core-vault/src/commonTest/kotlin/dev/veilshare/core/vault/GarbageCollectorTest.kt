package dev.veilshare.core.vault

import dev.veilshare.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GarbageCollectorTest { @Test fun `only journal-proven unreferenced blob is collectible`()=runTest { val a=BlobId("a");val b=BlobId("b");val c=BlobId("c");val blobs=MemoryBlobs(setOf(a,b,c));val journal=MemoryJournal(listOf(JournalEntry(TransactionId("tx"),TransactionState.VERIFYING,setOf(b),"namespace")));val gc=VaultGarbageCollector(blobs,journal);val scan=gc.scan(AuthenticatedVaultState(VaultId("vault"),"namespace",setOf(a)));assertEquals(BlobDisposition.REFERENCED_COMMITTED,scan.dispositions[a]);assertEquals(BlobDisposition.CONFIRMED_ORPHAN,scan.dispositions[b]);assertEquals(BlobDisposition.AMBIGUOUS,scan.dispositions[c]);gc.collect(scan);assertTrue(blobs.exists(a));assertFalse(blobs.exists(b));assertTrue(blobs.exists(c)) } }
private class MemoryJournal(entries:List<JournalEntry>):VaultJournal {private val e=entries.toMutableList();override suspend fun put(entry:JournalEntry){e.removeAll{it.id==entry.id};e+=entry};override suspend fun entries()=e.toList();override suspend fun remove(id:TransactionId){e.removeAll{it.id==id}}}
private class MemoryBlobs(ids:Set<BlobId>):BlobStore {private val ids=ids.toMutableSet();override suspend fun create()=error("unused");override suspend fun open(id:BlobId)=error("unused");override suspend fun exists(id:BlobId)=id in ids;override suspend fun size(id:BlobId)=0L;override suspend fun delete(id:BlobId){ids-=id};override suspend fun listIds()=ids.toSet()}
