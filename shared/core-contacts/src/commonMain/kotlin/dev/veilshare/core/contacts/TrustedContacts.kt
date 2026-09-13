package dev.veilshare.core.contacts

import dev.veilshare.core.crypto.Ed25519PublicKey
import dev.veilshare.core.crypto.Hash
import dev.veilshare.core.crypto.toHex
import dev.veilshare.core.model.ContactId
import dev.veilshare.core.model.Fingerprint
import dev.veilshare.core.model.OpaqueIds
import dev.veilshare.core.model.RandomBytesSource
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.SharingIdentityId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ContactVerificationMethod {
    MANUAL_FINGERPRINT,
    QR_CODE,
    EXISTING_TRUSTED_SESSION,
}

data class PeerIdentityCandidate(
    val sharingIdentityId: SharingIdentityId,
    val publicKey: Ed25519PublicKey,
    val referenceCode: ReferenceCode? = null,
) {
    init {
        require(publicKey.bytes.size == ED25519_PUBLIC_KEY_BYTES) { "Ed25519 public key must be 32 bytes" }
    }

    val fingerprint: Fingerprint
        get() = Fingerprint(Hash.sha256(publicKey.bytes).toHex())

    companion object {
        const val ED25519_PUBLIC_KEY_BYTES = 32
    }
}

data class TrustedContact(
    val contactId: ContactId,
    val alias: String,
    val sharingIdentityId: SharingIdentityId,
    val pinnedPublicKey: ByteArray,
    val fingerprint: Fingerprint,
    val verificationMethod: ContactVerificationMethod,
    val referenceCode: ReferenceCode? = null,
) {
    init {
        require(alias.isNotBlank()) { "Contact alias is required" }
        require(alias.length <= MAX_ALIAS_CHARS) { "Contact alias is too long" }
        require(alias.none { it.code < 0x20 || it.code == 0x7f }) { "Contact alias contains control characters" }
        require(pinnedPublicKey.size == PeerIdentityCandidate.ED25519_PUBLIC_KEY_BYTES) {
            "Pinned Ed25519 public key must be 32 bytes"
        }
        require(Fingerprint(Hash.sha256(pinnedPublicKey).toHex()) == fingerprint) {
            "Pinned fingerprint does not match public key"
        }
    }

    fun deepCopy(): TrustedContact = copy(pinnedPublicKey = pinnedPublicKey.copyOf())

    companion object {
        const val MAX_ALIAS_CHARS = 96
    }
}

data class PinnedPeerIdentity(
    val contactId: ContactId,
    val sharingIdentityId: SharingIdentityId,
    val sharingIdentityIdHash: String,
    val publicKey: Ed25519PublicKey,
    val fingerprint: Fingerprint,
)

enum class VerificationReason {
    NEW_PEER,
    IDENTITY_CHANGED_FOR_ROUTING_CODE,
}

sealed interface PeerTrustDecision {
    data class Trusted(
        val contact: TrustedContact,
        val binding: PinnedPeerIdentity,
    ) : PeerTrustDecision

    data class NeedsVerification(
        val candidate: PeerIdentityCandidate,
        val reason: VerificationReason,
        val existingContact: TrustedContact? = null,
    ) : PeerTrustDecision

    data class KeyMismatch(
        val contact: TrustedContact,
        val presentedFingerprint: Fingerprint,
    ) : PeerTrustDecision
}

interface TrustedContactStore {
    suspend fun get(contactId: ContactId): TrustedContact?
    suspend fun findByIdentity(identityId: SharingIdentityId): TrustedContact?
    suspend fun findByReferenceCode(referenceCode: ReferenceCode): TrustedContact?
    suspend fun all(): List<TrustedContact>
    suspend fun replace(contact: TrustedContact)
    suspend fun delete(contactId: ContactId)
}

class InMemoryTrustedContactStore : TrustedContactStore {
    private val mutex = Mutex()
    private val contacts = mutableMapOf<String, TrustedContact>()

    override suspend fun get(contactId: ContactId): TrustedContact? = mutex.withLock {
        contacts[contactId.value]?.deepCopy()
    }

    override suspend fun findByIdentity(identityId: SharingIdentityId): TrustedContact? = mutex.withLock {
        contacts.values.firstOrNull { it.sharingIdentityId == identityId }?.deepCopy()
    }

    override suspend fun findByReferenceCode(referenceCode: ReferenceCode): TrustedContact? = mutex.withLock {
        contacts.values.firstOrNull { it.referenceCode == referenceCode }?.deepCopy()
    }

    override suspend fun all(): List<TrustedContact> = mutex.withLock {
        contacts.values.map { it.deepCopy() }
    }

    override suspend fun replace(contact: TrustedContact) {
        mutex.withLock {
            contacts[contact.contactId.value] = contact.deepCopy()
        }
    }

    override suspend fun delete(contactId: ContactId) {
        mutex.withLock {
            contacts.remove(contactId.value)
        }
    }
}

/**
 * Trust boundary between signaling lookup results and the authenticated handshake.
 *
 * A ReferenceCode is only a routing token. It never upgrades trust and it never causes
 * a pinned identity/key to be replaced automatically.
 */
class TrustedContactManager(
    private val store: TrustedContactStore,
    private val random: RandomBytesSource,
) {
    private val mutex = Mutex()

    suspend fun evaluate(candidate: PeerIdentityCandidate): PeerTrustDecision = mutex.withLock {
        val byIdentity = store.findByIdentity(candidate.sharingIdentityId)
        if (byIdentity != null) {
            if (!byIdentity.pinnedPublicKey.contentEquals(candidate.publicKey.bytes)) {
                return@withLock PeerTrustDecision.KeyMismatch(
                    contact = byIdentity,
                    presentedFingerprint = candidate.fingerprint,
                )
            }
            return@withLock PeerTrustDecision.Trusted(
                contact = byIdentity,
                binding = byIdentity.toPinnedBinding(),
            )
        }

        val routedContact = candidate.referenceCode?.let { store.findByReferenceCode(it) }
        if (routedContact != null) {
            return@withLock PeerTrustDecision.NeedsVerification(
                candidate = candidate,
                reason = VerificationReason.IDENTITY_CHANGED_FOR_ROUTING_CODE,
                existingContact = routedContact,
            )
        }

        PeerTrustDecision.NeedsVerification(
            candidate = candidate,
            reason = VerificationReason.NEW_PEER,
        )
    }

    /**
     * Resolves only already-pinned identities from the privacy-preserving identity hash
     * carried by SESSION_HELLO. The public key from the HELLO itself is deliberately not
     * used as a trust source.
     */
    suspend fun findPinnedByIdentityHash(identityHash: String): PinnedPeerIdentity? = mutex.withLock {
        require(identityHash.isNotBlank()) { "Identity hash is required" }
        store.all()
            .firstOrNull {
                Hash.sha256(it.sharingIdentityId.value.encodeToByteArray()).toHex() == identityHash
            }
            ?.toPinnedBinding()
    }

    suspend fun addVerified(
        alias: String,
        candidate: PeerIdentityCandidate,
        verificationMethod: ContactVerificationMethod,
    ): TrustedContact = mutex.withLock {
        val safeAlias = validateAlias(alias)
        val existing = store.findByIdentity(candidate.sharingIdentityId)
        if (existing != null) {
            require(existing.pinnedPublicKey.contentEquals(candidate.publicKey.bytes)) {
                "Cannot replace a pinned key through addVerified"
            }
            return@withLock existing
        }

        candidate.referenceCode?.let { code ->
            require(store.findByReferenceCode(code) == null) {
                "Reference code is already associated with another trusted contact"
            }
        }

        val contact = TrustedContact(
            contactId = ContactId(OpaqueIds.fromRandom(random)),
            alias = safeAlias,
            sharingIdentityId = candidate.sharingIdentityId,
            pinnedPublicKey = candidate.publicKey.bytes.copyOf(),
            fingerprint = candidate.fingerprint,
            verificationMethod = verificationMethod,
            referenceCode = candidate.referenceCode,
        )
        store.replace(contact)
        contact.deepCopy()
    }

    /**
     * Explicit high-friction path for a user-confirmed key/identity replacement.
     * Callers must have performed a fresh out-of-band verification before invoking it.
     */
    suspend fun confirmIdentityChange(
        contactId: ContactId,
        candidate: PeerIdentityCandidate,
        verificationMethod: ContactVerificationMethod,
    ): TrustedContact = mutex.withLock {
        val current = requireNotNull(store.get(contactId)) { "Contact not found" }
        val identityOwner = store.findByIdentity(candidate.sharingIdentityId)
        require(identityOwner == null || identityOwner.contactId == contactId) {
            "Candidate identity is already pinned to another contact"
        }
        candidate.referenceCode?.let { code ->
            val routeOwner = store.findByReferenceCode(code)
            require(routeOwner == null || routeOwner.contactId == contactId) {
                "Candidate reference code is already associated with another contact"
            }
        }

        val replacement = current.copy(
            sharingIdentityId = candidate.sharingIdentityId,
            pinnedPublicKey = candidate.publicKey.bytes.copyOf(),
            fingerprint = candidate.fingerprint,
            verificationMethod = verificationMethod,
            referenceCode = candidate.referenceCode,
        )
        store.replace(replacement)
        replacement.deepCopy()
    }

    /** Only call after an authenticated session with the already pinned identity. */
    suspend fun updateReferenceCodeFromAuthenticatedSession(
        contactId: ContactId,
        referenceCode: ReferenceCode,
    ): TrustedContact = mutex.withLock {
        val current = requireNotNull(store.get(contactId)) { "Contact not found" }
        val routeOwner = store.findByReferenceCode(referenceCode)
        require(routeOwner == null || routeOwner.contactId == contactId) {
            "Reference code is already associated with another contact"
        }
        val updated = current.copy(referenceCode = referenceCode)
        store.replace(updated)
        updated.deepCopy()
    }

    suspend fun rename(contactId: ContactId, alias: String): TrustedContact = mutex.withLock {
        val current = requireNotNull(store.get(contactId)) { "Contact not found" }
        val updated = current.copy(alias = validateAlias(alias))
        store.replace(updated)
        updated.deepCopy()
    }

    suspend fun remove(contactId: ContactId) {
        mutex.withLock { store.delete(contactId) }
    }

    suspend fun all(): List<TrustedContact> = mutex.withLock { store.all() }

    private fun TrustedContact.toPinnedBinding(): PinnedPeerIdentity = PinnedPeerIdentity(
        contactId = contactId,
        sharingIdentityId = sharingIdentityId,
        sharingIdentityIdHash = Hash.sha256(sharingIdentityId.value.encodeToByteArray()).toHex(),
        publicKey = Ed25519PublicKey(pinnedPublicKey.copyOf()),
        fingerprint = fingerprint,
    )

    private fun validateAlias(alias: String): String {
        val trimmed = alias.trim()
        require(trimmed.isNotEmpty()) { "Contact alias is required" }
        require(trimmed.length <= TrustedContact.MAX_ALIAS_CHARS) { "Contact alias is too long" }
        require(trimmed.none { it.code < 0x20 || it.code == 0x7f }) { "Contact alias contains control characters" }
        return trimmed
    }
}

fun Fingerprint.toDisplayGroups(groupSize: Int = 4): String {
    require(groupSize > 0)
    return value.uppercase().chunked(groupSize).joinToString(" ")
}
