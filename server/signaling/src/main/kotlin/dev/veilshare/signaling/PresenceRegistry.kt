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
    // These two maps form one logical routing index. Every public operation is synchronized
    // on this registry instance so REGISTER/LOOKUP/disconnect cleanup cannot observe or leave
    // partially-updated state across concurrently handled WebSocket connections.
    private val byReferenceCode = linkedMapOf<ReferenceCode, PresenceEntry>()
    private val byConnection = linkedMapOf<ConnectionId, MutableSet<ReferenceCode>>()

    @Synchronized
    fun register(
        referenceCode: ReferenceCode,
        connectionId: ConnectionId,
        sharingIdentityId: SharingIdentityId,
        sharingPublicKey: String,
    ): PresenceEntry {
        cleanupExpiredLocked()
        require(sharingPublicKey.isNotBlank() && sharingPublicKey.length <= 512)
        val existing = byReferenceCode[referenceCode]
        // REGISTER contains only public identity material. A different live connection must
        // never be allowed to take over a routing code merely by copying identityId/publicKey
        // learned through LOOKUP. Reconnection first closes the old socket; server cleanup
        // releases its route, after which the new connection can register the same code.
        require(existing == null || existing.connectionId == connectionId) {
            "Reference code already registered"
        }
        require(byReferenceCode.size < limits.maxPresenceEntries || existing != null) { "Presence registry full" }
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

    @Synchronized
    fun lookup(referenceCode: ReferenceCode): PresenceEntry? {
        cleanupExpiredLocked()
        return byReferenceCode[referenceCode]
    }

    @Synchronized
    fun lookupByIdentity(sharingIdentityId: SharingIdentityId): List<PresenceEntry> {
        cleanupExpiredLocked()
        return byReferenceCode.values.filter { it.sharingIdentityId == sharingIdentityId }
    }

    @Synchronized
    fun unregister(referenceCode: ReferenceCode, connectionId: ConnectionId): Boolean {
        val current = byReferenceCode[referenceCode] ?: return false
        if (current.connectionId != connectionId) return false
        byReferenceCode.remove(referenceCode)
        byConnection[connectionId]?.remove(referenceCode)
        if (byConnection[connectionId]?.isEmpty() == true) byConnection.remove(connectionId)
        return true
    }

    @Synchronized
    fun unregisterConnection(connectionId: ConnectionId): Int {
        val codes = byConnection.remove(connectionId).orEmpty()
        var removed = 0
        codes.forEach { code ->
            // A delayed close must never erase a route now owned by another connection.
            if (byReferenceCode[code]?.connectionId == connectionId) {
                byReferenceCode.remove(code)
                removed++
            }
        }
        return removed
    }

    @Synchronized
    fun cleanupExpired(): Int = cleanupExpiredLocked()

    @Synchronized
    fun size(): Int {
        cleanupExpiredLocked()
        return byReferenceCode.size
    }

    private fun cleanupExpiredLocked(): Int {
        val now = clock.nowMillis()
        val expired = byReferenceCode.values
            .filter { it.expiresAtMillis <= now }
            .map { it.referenceCode }
        expired.forEach { code ->
            val entry = byReferenceCode.remove(code)
            if (entry != null) {
                byConnection[entry.connectionId]?.remove(code)
                if (byConnection[entry.connectionId]?.isEmpty() == true) byConnection.remove(entry.connectionId)
            }
        }
        return expired.size
    }
}
