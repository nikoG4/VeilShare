package dev.veilshare.core.identity

import dev.veilshare.core.model.LocalPersonaId
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.securestore.BinaryStateReader
import dev.veilshare.core.securestore.BinaryStateWriter
import dev.veilshare.core.securestore.ProtectedStateStore
import dev.veilshare.core.securestore.SecureStateScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SharingContextBindingStore {
    suspend fun load(personaId: LocalPersonaId): SharingContextId?
    suspend fun replace(personaId: LocalPersonaId, contextId: SharingContextId)
    suspend fun delete(personaId: LocalPersonaId)
}

class InMemorySharingContextBindingStore : SharingContextBindingStore {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, SharingContextId>()

    override suspend fun load(personaId: LocalPersonaId): SharingContextId? = mutex.withLock {
        records[personaId.value]
    }

    override suspend fun replace(personaId: LocalPersonaId, contextId: SharingContextId) {
        mutex.withLock {
            require(records.entries.none { it.key != personaId.value && it.value == contextId }) {
                "Sharing context is already bound to another local persona"
            }
            records[personaId.value] = contextId
        }
    }

    override suspend fun delete(personaId: LocalPersonaId) {
        mutex.withLock { records.remove(personaId.value) }
    }
}

/** Resolves one stable random SharingContextId for each authenticated local vault persona. */
class SharingContextBindingManager(
    private val store: SharingContextBindingStore,
    private val random: RandomBytesSource,
) {
    private val mutex = Mutex()

    suspend fun getOrCreate(personaId: LocalPersonaId): SharingContextId = mutex.withLock {
        store.load(personaId)?.let { return@withLock it }
        val contextId = SharingContextId(OpaqueIds.fromRandom(random))
        store.replace(personaId, contextId)
        contextId
    }

    suspend fun existing(personaId: LocalPersonaId): SharingContextId? = mutex.withLock {
        store.load(personaId)
    }

    suspend fun delete(personaId: LocalPersonaId) {
        mutex.withLock { store.delete(personaId) }
    }
}

/**
 * Protected persistent mapping from local vault-persona binding token to random sharing
 * context. The persona token is only a local lookup key; neither side of this mapping is
 * transmitted to signaling as a vault identifier.
 */
class PersistentSharingContextBindingStore(
    private val protectedStore: ProtectedStateStore,
) : SharingContextBindingStore {
    private val mutex = Mutex()

    override suspend fun load(personaId: LocalPersonaId): SharingContextId? = mutex.withLock {
        readAll()[personaId.value]
    }

    override suspend fun replace(personaId: LocalPersonaId, contextId: SharingContextId) {
        mutex.withLock {
            val records = readAll()
            require(records.entries.none { it.key != personaId.value && it.value == contextId }) {
                "Sharing context is already bound to another local persona"
            }
            records[personaId.value] = contextId
            require(records.size <= MAX_BINDINGS) { "Too many persisted sharing context bindings" }
            writeAll(records)
        }
    }

    override suspend fun delete(personaId: LocalPersonaId) {
        mutex.withLock {
            val records = readAll()
            records.remove(personaId.value)
            if (records.isEmpty()) protectedStore.delete(SCOPE) else writeAll(records)
        }
    }

    private suspend fun readAll(): MutableMap<String, SharingContextId> {
        val plaintext = protectedStore.read(SCOPE) ?: return linkedMapOf()
        return try {
            decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun writeAll(records: Map<String, SharingContextId>) {
        val plaintext = encode(records)
        try {
            protectedStore.write(SCOPE, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        internal val SCOPE = SecureStateScope("sharing.context-bindings.v1")
        internal const val MAX_BINDINGS = 32
    }
}

private fun encode(records: Map<String, SharingContextId>): ByteArray {
    require(records.size <= PersistentSharingContextBindingStore.MAX_BINDINGS)
    val writer = BinaryStateWriter()
    return try {
        writer.writeInt(BINDING_MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeInt(records.size)
        records.toSortedMap().forEach { (personaId, contextId) ->
            writer.writeString(personaId, PERSONA_ID_BYTES)
            writer.writeString(contextId.value, CONTEXT_ID_BYTES)
        }
        writer.toByteArray()
    } finally {
        writer.close()
    }
}

private fun decode(bytes: ByteArray): MutableMap<String, SharingContextId> {
    val reader = BinaryStateReader(bytes)
    require(reader.readInt() == BINDING_MAGIC) { "Sharing context binding state magic mismatch" }
    require(reader.readInt() == FORMAT_VERSION) { "Unsupported sharing context binding version" }
    val count = reader.readInt()
    require(count in 0..PersistentSharingContextBindingStore.MAX_BINDINGS) { "Invalid sharing context binding count" }
    val result = linkedMapOf<String, SharingContextId>()
    repeat(count) {
        val persona = LocalPersonaId(reader.readString(PERSONA_ID_BYTES))
        val context = SharingContextId(reader.readString(CONTEXT_ID_BYTES))
        require(result.put(persona.value, context) == null) { "Duplicate local persona binding" }
        require(result.values.count { it == context } == 1) { "Duplicate sharing context binding" }
    }
    reader.requireFinished()
    return result
}

private const val FORMAT_VERSION = 1
private const val BINDING_MAGIC = 0x56534342 // VSCB
private const val PERSONA_ID_BYTES = 64
private const val CONTEXT_ID_BYTES = 128
