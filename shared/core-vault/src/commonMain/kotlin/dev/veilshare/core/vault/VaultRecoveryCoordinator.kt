package dev.veilshare.core.vault

/** Phase A is inspection-only; Phase B is authorized only by an authenticated catalog. */
class VaultRecoveryCoordinator(private val journal: VaultJournal, private val garbage: VaultGarbageCollector) {
    suspend fun reconcileAuthenticated(state: AuthenticatedVaultState): Set<TransactionId> {
        val entries=journal.entries().filter { it.blobNamespace==state.blobNamespace }
        val alreadyCommitted=entries.filter { entry -> entry.provisionalBlobs.any { it in state.referencedBlobs } }
        // A referenced blob always wins over a stale/pending journal entry.
        alreadyCommitted.forEach { journal.remove(it.id) }
        val scan=garbage.scan(state); val deleted=garbage.collect(scan)
        journal.entries().filter { it.blobNamespace==state.blobNamespace && it.provisionalBlobs.all { b -> b in deleted || !garbageIsPresent(scan,b) } }.forEach { journal.remove(it.id) }
        return alreadyCommitted.map { it.id }.toSet()
    }

    private fun garbageIsPresent(scan:GarbageScan,id:dev.veilshare.core.model.BlobId)=scan.dispositions.containsKey(id)

}
