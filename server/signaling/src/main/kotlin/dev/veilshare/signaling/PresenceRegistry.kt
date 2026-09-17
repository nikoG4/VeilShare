package dev.veilshare.signaling

import dev.veilshare.core.model.ConnectionId
import dev.veilshare.core.model.ReferenceCode
import dev.veilshare.core.model.SharingIdentityId

data class PresenceEntry(
    val referenceCode: ReferenceCode,
    val connectionId: ConnectionId,
    val sharingIdentityId: SharingIdentityId,
    val sharingPublicKey: String,
    val expiresAtMillis: Long,
)

class PresenceRegistry(
    private val clock: SignalingClock,
    private val limits: SignalingLimits = SignalingLimits(),
) {
    private val byReferenceCode = linkedMapOf<ReferenceCode, PresenceEntry>()
    private val byConnection = linkedMapOf<ConnectionId, MutableSet<ReferenceCode>>()

    fun register(
        referenceCode: ReferenceCode,
        connectionId: ConnectionId,
        sharingIdentityId: SharingIdentityId,
        sharingPublicKey: String,
    ): PresenceEntry {
        cleanupExpired()
        require(sharingPublicKey.isNotBlank() && sharingPublicKey.length <= 512)
        val existing = byReferenceCode[referenceCode]
        val sameOwner = existing != null &&
            existing.sharingIdentityId == sharingIdentityId &&
            existing.sharingPublicKey == sharingPublicKey
        require(existing == null || existing.connectionId == connectionId || sameOwner) {
            "Reference code already registered"
        }
        require(byReferenceCode.size < limits.maxPresenceEntries || existing != null) { "Presence registry full" }
        if (existing != null && existing.connectionId != connectionId) {
            byConnection[existing.connectionId]?.remove(referenceCode)
            if (byConnection[existing.connectionId]?.isEmpty() == true) {
                byConnection.remove(existing.connectionId)
            }
        }
        val ownedCodes = byConnection.getOrPut(connectionId) { linkedSetOf() }
        require(ownedCodes.size < limits.maxRegistrationsPerConnection || referenceCode in ownedCodes) {
            "Connection registration limit exceeded"
        }
        val entry = PresenceEntry(
            referenceCode = referenceCode,
            connectionId = connectionId,
            sharingIdentityId = sharingIdentityId,
            sharingPublicKey = sharingPublicKey,
            expiresAtMillis = clock.nowMillis() + limits.presenceTtlMillis,
        )
        byReferenceCode[referenceCode] = entry
        ownedCodes += referenceCode
        return entry
    }

    fun lookup(referenceCode: ReferenceCode): PresenceEntry? {
        cleanupExpired()
        return byReferenceCode[referenceCode]
    }

    fun lookupByIdentity(sharingIdentityId: SharingIdentityId): List<PresenceEntry> {
        cleanupExpired()
        return byReferenceCode.values.filter { it.sharingIdentityId == sharingIdentityId }
    }

    fun unregister(referenceCode: ReferenceCode, connectionId: ConnectionId): Boolean {
        val current = byReferenceCode[referenceCode] ?: return false
        if (current.connectionId != connectionId) return false
        byReferenceCode.remove(referenceCode)
        byConnection[connectionId]?.remove(referenceCode)
        if (byConnection[connectionId]?.isEmpty() == true) byConnection.remove(connectionId)
        return true
    }

    fun unregisterConnection(connectionId: ConnectionId) {
        val codes = byConnection.remove(connectionId).orEmpty()
        codes.forEach { code ->
            // A delayed close from an old socket must not erase a newer registration.
            if (byReferenceCode[code]?.connectionId == connectionId) {
                byReferenceCode.remove(code)
            }
        }
    }

    fun cleanupExpired(): Int {
        val now = clock.nowMillis()
        val expired = byReferenceCode.values.filter { it.expiresAtMillis <= now }.map { it.referenceCode }
        expired.forEach { code ->
            val entry = byReferenceCode.remove(code)
            if (entry != null) {
                byConnection[entry.connectionId]?.remove(code)
                if (byConnection[entry.connectionId]?.isEmpty() == true) byConnection.remove(entry.connectionId)
            }
        }
        return expired.size
    }

    fun size(): Int {
        cleanupExpired()
        return byReferenceCode.size
    }
}
