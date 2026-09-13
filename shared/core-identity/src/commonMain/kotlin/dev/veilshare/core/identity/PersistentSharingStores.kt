package dev.veilshare.core.identity

import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.securestore.BinaryStateReader
import dev.veilshare.core.securestore.BinaryStateWriter
import dev.veilshare.core.securestore.ProtectedStateStore
import dev.veilshare.core.securestore.SecureStateScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Encrypted persistent implementation for sharing identities.
 *
 * The private Ed25519 seed is encoded only as raw bytes inside the protected plaintext
 * buffer, never as String/Base64. All decoded/encoded secret buffers are zeroized best-effort.
 */
class PersistentSharingIdentityStore(
    private val protectedStore: ProtectedStateStore,
) : SharingIdentityStore {
    private val mutex = Mutex()

    override suspend fun load(contextId: SharingContextId): StoredSharingIdentity? = mutex.withLock {
        val records = readAll()
        try {
            records[contextId.value]?.deepCopy()
        } finally {
            clearRecords(records)
        }
    }

    override suspend fun replace(record: StoredSharingIdentity) {
        mutex.withLock {
            val records = readAll()
            try {
                records.put(record.contextId.value, record.deepCopy())?.clearPrivateMaterial()
                require(records.size <= MAX_IDENTITIES) { "Too many persisted sharing identities" }
                writeAll(records)
            } finally {
                clearRecords(records)
            }
        }
    }

    override suspend fun delete(contextId: SharingContextId) {
        mutex.withLock {
            val records = readAll()
            try {
                records.remove(contextId.value)?.clearPrivateMaterial()
                if (records.isEmpty()) protectedStore.delete(IDENTITY_SCOPE) else writeAll(records)
            } finally {
                clearRecords(records)
            }
        }
    }

    private suspend fun readAll(): MutableMap<String, StoredSharingIdentity> {
        val plaintext = protectedStore.read(IDENTITY_SCOPE) ?: return linkedMapOf()
        return try {
            decodeIdentities(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun writeAll(records: Map<String, StoredSharingIdentity>) {
        val plaintext = encodeIdentities(records.values.sortedBy { it.contextId.value })
        try {
            protectedStore.write(IDENTITY_SCOPE, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun clearRecords(records: Map<String, StoredSharingIdentity>) {
        records.values.forEach { it.clearPrivateMaterial() }
    }

    companion object {
        internal val IDENTITY_SCOPE = SecureStateScope("sharing.identity.v1")
        internal const val MAX_IDENTITIES = 32
    }
}

class PersistentSharingPresenceStore(
    private val protectedStore: ProtectedStateStore,
) : SharingPresenceStore {
    private val mutex = Mutex()

    override suspend fun load(contextId: SharingContextId): SharingPresence? = mutex.withLock {
        readAll()[contextId.value]
    }

    override suspend fun replace(presence: SharingPresence) {
        mutex.withLock {
            val records = readAll()
            records[presence.contextId.value] = presence
            require(records.size <= MAX_PRESENCES) { "Too many persisted sharing presences" }
            writeAll(records)
        }
    }

    override suspend fun delete(contextId: SharingContextId) {
        mutex.withLock {
            val records = readAll()
            records.remove(contextId.value)
            if (records.isEmpty()) protectedStore.delete(PRESENCE_SCOPE) else writeAll(records)
        }
    }

    private suspend fun readAll(): MutableMap<String, SharingPresence> {
        val plaintext = protectedStore.read(PRESENCE_SCOPE) ?: return linkedMapOf()
        return try {
            decodePresences(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun writeAll(records: Map<String, SharingPresence>) {
        val plaintext = encodePresences(records.values.sortedBy { it.contextId.value })
        try {
            protectedStore.write(PRESENCE_SCOPE, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        internal val PRESENCE_SCOPE = SecureStateScope("sharing.presence.v1")
        internal const val MAX_PRESENCES = 32
    }
}

private fun encodeIdentities(records: List<StoredSharingIdentity>): ByteArray {
    require(records.size <= PersistentSharingIdentityStore.MAX_IDENTITIES)
    val writer = BinaryStateWriter()
    return try {
        writer.writeInt(IDENTITY_MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeInt(records.size)
        records.forEach { record ->
            writer.writeString(record.contextId.value, MAX_ID_TEXT_BYTES)
            writer.writeString(record.identityId.value, MAX_ID_TEXT_BYTES)
            writer.writeBytes(record.privateKeySeed, StoredSharingIdentity.ED25519_KEY_BYTES)
            writer.writeBytes(record.publicKey, StoredSharingIdentity.ED25519_KEY_BYTES)
        }
        writer.toByteArray()
    } finally {
        writer.close()
    }
}

private fun decodeIdentities(bytes: ByteArray): MutableMap<String, StoredSharingIdentity> {
    val reader = BinaryStateReader(bytes)
    require(reader.readInt() == IDENTITY_MAGIC) { "Sharing identity state magic mismatch" }
    require(reader.readInt() == FORMAT_VERSION) { "Unsupported sharing identity state version" }
    val count = reader.readInt()
    require(count in 0..PersistentSharingIdentityStore.MAX_IDENTITIES) { "Invalid sharing identity count" }
    val result = linkedMapOf<String, StoredSharingIdentity>()
    try {
        repeat(count) {
            val contextId = SharingContextId(reader.readString(MAX_ID_TEXT_BYTES))
            val identityId = SharingIdentityId(reader.readString(MAX_ID_TEXT_BYTES))
            val privateSeed = reader.readBytes(StoredSharingIdentity.ED25519_KEY_BYTES)
            val publicKey = reader.readBytes(StoredSharingIdentity.ED25519_KEY_BYTES)
            val record = try {
                StoredSharingIdentity(contextId, identityId, privateSeed, publicKey)
            } catch (failure: Throwable) {
                privateSeed.fill(0)
                publicKey.fill(0)
                throw failure
            }
            require(result.put(contextId.value, record) == null) { "Duplicate sharing identity context" }
        }
        reader.requireFinished()
        return result
    } catch (failure: Throwable) {
        result.values.forEach { it.clearPrivateMaterial() }
        throw failure
    }
}

private fun encodePresences(records: List<SharingPresence>): ByteArray {
    require(records.size <= PersistentSharingPresenceStore.MAX_PRESENCES)
    val writer = BinaryStateWriter()
    return try {
        writer.writeInt(PRESENCE_MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeInt(records.size)
        records.forEach { record ->
            writer.writeString(record.contextId.value, MAX_ID_TEXT_BYTES)
            writer.writeString(record.referenceCode.value, MAX_REFERENCE_CODE_BYTES)
        }
        writer.toByteArray()
    } finally {
        writer.close()
    }
}

private fun decodePresences(bytes: ByteArray): MutableMap<String, SharingPresence> {
    val reader = BinaryStateReader(bytes)
    require(reader.readInt() == PRESENCE_MAGIC) { "Sharing presence state magic mismatch" }
    require(reader.readInt() == FORMAT_VERSION) { "Unsupported sharing presence state version" }
    val count = reader.readInt()
    require(count in 0..PersistentSharingPresenceStore.MAX_PRESENCES) { "Invalid sharing presence count" }
    val result = linkedMapOf<String, SharingPresence>()
    repeat(count) {
        val context = SharingContextId(reader.readString(MAX_ID_TEXT_BYTES))
        val code = ReferenceCodes.parse(reader.readString(MAX_REFERENCE_CODE_BYTES))
        require(result.put(context.value, SharingPresence(context, code)) == null) { "Duplicate sharing presence context" }
    }
    reader.requireFinished()
    return result
}

private const val FORMAT_VERSION = 1
private const val IDENTITY_MAGIC = 0x56534931 // VSI1
private const val PRESENCE_MAGIC = 0x56535031 // VSP1
private const val MAX_ID_TEXT_BYTES = 256
private const val MAX_REFERENCE_CODE_BYTES = 64
