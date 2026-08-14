package dev.veilshare.core.vault

sealed interface VaultConsistency { data object Healthy:VaultConsistency; data object NeedsCleanup:VaultConsistency; data object Damaged:VaultConsistency; data object CorruptCatalog:VaultConsistency; data object CorruptJournal:VaultConsistency; data object Unsupported:VaultConsistency }
/** This deliberately does not decrypt every file: missing references are damage; authenticated GC findings are cleanup. */
class VaultConsistencyChecker(private val blobs:BlobStore, private val garbage:VaultGarbageCollector) {
 suspend fun check(state:AuthenticatedVaultState):VaultConsistency { if(state.referencedBlobs.any { !blobs.exists(it) }) return VaultConsistency.Damaged; val scan=garbage.scan(state); return when { scan.dispositions.values.any { it==BlobDisposition.CONFIRMED_ORPHAN || it==BlobDisposition.STAGING } -> VaultConsistency.NeedsCleanup; else -> VaultConsistency.Healthy } }
}
