package dev.veilshare.core.vault
import dev.veilshare.core.model.BlobId
@JvmInline value class TransactionId(val value: String) { init { require(value.isNotBlank()) } }
enum class TransactionState { PREPARING, WRITING, VERIFYING, COMMITTED, ABORTED }
data class JournalEntry(val id: TransactionId, val state: TransactionState, val provisionalBlobs: Set<BlobId>, val blobNamespace:String = "")
interface VaultJournal { suspend fun put(entry: JournalEntry); suspend fun entries(): List<JournalEntry>; suspend fun remove(id: TransactionId) }
/** Built only after slot and catalog authentication; filenames never reach recovery. */
data class AuthenticatedVaultState(val vaultId: dev.veilshare.core.model.VaultId, val blobNamespace:String, val referencedBlobs:Set<BlobId>) { init { require(blobNamespace.isNotBlank()) } }
enum class BlobDisposition { REFERENCED_COMMITTED, CONFIRMED_ORPHAN, STAGING, AMBIGUOUS }
data class GarbageScan(val dispositions:Map<BlobId,BlobDisposition>)
class VaultGarbageCollector(private val blobs: BlobStore, private val journal: VaultJournal) {
    /** Requires a catalog-authenticated state for this exact opaque namespace. */
    suspend fun scan(state:AuthenticatedVaultState): GarbageScan { val incomplete=journal.entries().filter { it.blobNamespace==state.blobNamespace && it.state != TransactionState.COMMITTED }.flatMap { it.provisionalBlobs }.toSet(); return GarbageScan(blobs.listIds().associateWith { id -> when { id in state.referencedBlobs -> BlobDisposition.REFERENCED_COMMITTED; id in incomplete -> BlobDisposition.CONFIRMED_ORPHAN; else -> BlobDisposition.AMBIGUOUS } }) }
    suspend fun collect(scan:GarbageScan):Set<BlobId> { val safe=scan.dispositions.filterValues { it==BlobDisposition.CONFIRMED_ORPHAN || it==BlobDisposition.STAGING }.keys; safe.forEach { if(blobs.exists(it)) blobs.delete(it) }; return safe }
}
