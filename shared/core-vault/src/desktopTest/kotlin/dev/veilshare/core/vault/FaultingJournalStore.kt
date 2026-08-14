package dev.veilshare.core.vault

/** Test-only wrapper that fails the real journal cleanup without replacing persistence. */
class FaultingJournalStore(
    private val delegate: VaultJournal,
    private val failRemoveBeforeDelegate: Boolean = false,
) : VaultJournal {
    override suspend fun put(entry: JournalEntry) = delegate.put(entry)

    override suspend fun entries(): List<JournalEntry> = delegate.entries()

    override suspend fun remove(id: TransactionId) {
        if (failRemoveBeforeDelegate) throw IllegalStateException("injected journal cleanup failure")
        delegate.remove(id)
    }
}
