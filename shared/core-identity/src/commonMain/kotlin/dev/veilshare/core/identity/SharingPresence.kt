package dev.veilshare.core.identity

import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.ReferenceCodes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SharingPresence(
    val contextId: SharingContextId,
    val referenceCode: ReferenceCode,
)

interface SharingPresenceStore {
    suspend fun load(contextId: SharingContextId): SharingPresence?
    suspend fun replace(presence: SharingPresence)
    suspend fun delete(contextId: SharingContextId)
}

class InMemorySharingPresenceStore : SharingPresenceStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, SharingPresence>()

    override suspend fun load(contextId: SharingContextId): SharingPresence? = mutex.withLock {
        entries[contextId.value]
    }

    override suspend fun replace(presence: SharingPresence) {
        mutex.withLock {
            entries[presence.contextId.value] = presence
        }
    }

    override suspend fun delete(contextId: SharingContextId) {
        mutex.withLock {
            entries.remove(contextId.value)
        }
    }
}

/**
 * Owns the random routing code for one sharing context.
 *
 * ReferenceCode is intentionally independent from SharingIdentityId and the Ed25519 key.
 * Rotating one must not implicitly rotate the other.
 */
class SharingPresenceManager(
    private val store: SharingPresenceStore,
    private val random: RandomBytesSource,
) {
    private val mutex = Mutex()

    suspend fun getOrCreate(contextId: SharingContextId): SharingPresence = mutex.withLock {
        store.load(contextId) ?: generate(contextId).also { store.replace(it) }
    }

    suspend fun rotate(contextId: SharingContextId): SharingPresence = mutex.withLock {
        generate(contextId).also { store.replace(it) }
    }

    suspend fun delete(contextId: SharingContextId) {
        mutex.withLock { store.delete(contextId) }
    }

    private fun generate(contextId: SharingContextId): SharingPresence = SharingPresence(
        contextId = contextId,
        referenceCode = ReferenceCodes.generate(random),
    )
}
