package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.ContactId
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.ReferenceCodes
import dev.veilshare.core.model.SharingIdentityId
import dev.veilshare.core.securestore.BinaryStateReader
import dev.veilshare.core.securestore.BinaryStateWriter
import dev.veilshare.core.securestore.ProtectedStateStore
import dev.veilshare.core.securestore.SecureStateScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Encrypted-at-rest trusted-contact store. Aliases, pinned identities and routes are protected. */
class PersistentTrustedContactStore(
    private val protectedStore: ProtectedStateStore,
) : TrustedContactStore {
    private val mutex = Mutex()

    override suspend fun get(contactId: ContactId): TrustedContact? = mutex.withLock {
        readAll()[contactId.value]?.deepCopy()
    }

    override suspend fun findByIdentity(identityId: SharingIdentityId): TrustedContact? = mutex.withLock {
        readAll().values.firstOrNull { it.sharingIdentityId == identityId }?.deepCopy()
    }

    override suspend fun findByReferenceCode(referenceCode: dev.veilshare.core.model.ReferenceCode): TrustedContact? = mutex.withLock {
        readAll().values.firstOrNull { it.referenceCode == referenceCode }?.deepCopy()
    }

    override suspend fun all(): List<TrustedContact> = mutex.withLock {
        readAll().values.map { it.deepCopy() }
    }

    override suspend fun replace(contact: TrustedContact) {
        mutex.withLock {
            val records = readAll()
            records[contact.contactId.value] = contact.deepCopy()
            require(records.size <= MAX_CONTACTS) { "Too many persisted trusted contacts" }
            validateUniqueBindings(records.values)
            writeAll(records)
        }
    }

    override suspend fun delete(contactId: ContactId) {
        mutex.withLock {
            val records = readAll()
            records.remove(contactId.value)
            if (records.isEmpty()) protectedStore.delete(CONTACT_SCOPE) else writeAll(records)
        }
    }

    private suspend fun readAll(): MutableMap<String, TrustedContact> {
        val plaintext = protectedStore.read(CONTACT_SCOPE) ?: return linkedMapOf()
        return try {
            decodeContacts(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun writeAll(records: Map<String, TrustedContact>) {
        val plaintext = encodeContacts(records.values.sortedBy { it.contactId.value })
        try {
            protectedStore.write(CONTACT_SCOPE, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        internal val CONTACT_SCOPE = SecureStateScope("sharing.contacts.v1")
        internal const val MAX_CONTACTS = 1024
    }
}

private fun validateUniqueBindings(contacts: Collection<TrustedContact>) {
    val identities = mutableSetOf<String>()
    val routes = mutableSetOf<String>()
    contacts.forEach { contact ->
        require(identities.add(contact.sharingIdentityId.value)) {
            "Duplicate trusted sharing identity in persistent store"
        }
        contact.referenceCode?.let { code ->
            require(routes.add(code.value)) { "Duplicate trusted reference code in persistent store" }
        }
    }
}

private fun encodeContacts(contacts: List<TrustedContact>): ByteArray {
    require(contacts.size <= PersistentTrustedContactStore.MAX_CONTACTS)
    val writer = BinaryStateWriter()
    return try {
        writer.writeInt(CONTACT_MAGIC)
        writer.writeInt(CONTACT_FORMAT_VERSION)
        writer.writeInt(contacts.size)
        contacts.forEach { contact ->
            writer.writeString(contact.contactId.value, MAX_ID_BYTES)
            writer.writeString(contact.alias, MAX_ALIAS_BYTES)
            writer.writeString(contact.sharingIdentityId.value, MAX_ID_BYTES)
            writer.writeBytes(contact.pinnedPublicKey, PeerIdentityCandidate.ED25519_PUBLIC_KEY_BYTES)
            writer.writeString(contact.verificationMethod.name, MAX_ENUM_BYTES)
            writer.writeNullableString(contact.referenceCode?.value, MAX_REFERENCE_CODE_BYTES)
        }
        writer.toByteArray()
    } finally {
        writer.close()
    }
}

private fun decodeContacts(bytes: ByteArray): MutableMap<String, TrustedContact> {
    val reader = BinaryStateReader(bytes)
    require(reader.readInt() == CONTACT_MAGIC) { "Trusted contact state magic mismatch" }
    require(reader.readInt() == CONTACT_FORMAT_VERSION) { "Unsupported trusted contact state version" }
    val count = reader.readInt()
    require(count in 0..PersistentTrustedContactStore.MAX_CONTACTS) { "Invalid trusted contact count" }
    val result = linkedMapOf<String, TrustedContact>()
    repeat(count) {
        val contactId = ContactId(reader.readString(MAX_ID_BYTES))
        val alias = reader.readString(MAX_ALIAS_BYTES)
        val identityId = SharingIdentityId(reader.readString(MAX_ID_BYTES))
        val pinnedKey = reader.readBytes(PeerIdentityCandidate.ED25519_PUBLIC_KEY_BYTES)
        val method = try {
            ContactVerificationMethod.valueOf(reader.readString(MAX_ENUM_BYTES))
        } catch (failure: Throwable) {
            pinnedKey.fill(0)
            throw IllegalArgumentException("Unknown contact verification method", failure)
        }
        val route = reader.readNullableString(MAX_REFERENCE_CODE_BYTES)?.let(ReferenceCodes::parse)
        val contact = try {
            TrustedContact(
                contactId = contactId,
                alias = alias,
                sharingIdentityId = identityId,
                pinnedPublicKey = pinnedKey,
                fingerprint = Fingerprint(Hash.sha256(pinnedKey).toHex()),
                verificationMethod = method,
                referenceCode = route,
            )
        } catch (failure: Throwable) {
            pinnedKey.fill(0)
            throw failure
        }
        require(result.put(contactId.value, contact) == null) { "Duplicate trusted contact id" }
    }
    reader.requireFinished()
    validateUniqueBindings(result.values)
    return result
}

private const val CONTACT_MAGIC = 0x56534331 // VSC1
private const val CONTACT_FORMAT_VERSION = 1
private const val MAX_ID_BYTES = 256
private const val MAX_ALIAS_BYTES = 384
private const val MAX_ENUM_BYTES = 64
private const val MAX_REFERENCE_CODE_BYTES = 64
