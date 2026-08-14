package dev.veilshare.core.vault

import dev.veilshare.core.model.BlobId
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentJournalTest { @Test fun `journal survives recreation and recovery removes unfinished transaction`()=runTest {
    val root=Files.createTempDirectory("veil-journal-"); val journal=DesktopVaultJournal(root); val blob=BlobId("opaque-blob")
    journal.put(JournalEntry(TransactionId("tx"),TransactionState.WRITING,setOf(blob),"n"))
    assertEquals(1,DesktopVaultJournal(root).entries().size)
    val store=FakeBlobStore(setOf(blob)); val recovered=VaultRecoveryCoordinator(DesktopVaultJournal(root),VaultGarbageCollector(store,DesktopVaultJournal(root))).reconcileAuthenticated(AuthenticatedVaultState(dev.veilshare.core.model.VaultId("v"),"n",emptySet()))
    assertTrue(recovered.isEmpty()); assertFalse(store.exists(blob)); assertTrue(DesktopVaultJournal(root).entries().isEmpty())
} }
private class FakeBlobStore(initial:Set<BlobId>):BlobStore { private val ids=initial.toMutableSet(); override suspend fun create()=error("unused"); override suspend fun open(id:BlobId)=error("unused"); override suspend fun exists(id:BlobId)=id in ids; override suspend fun size(id:BlobId)=0L; override suspend fun delete(id:BlobId){ids-=id}; override suspend fun listIds()=ids }
