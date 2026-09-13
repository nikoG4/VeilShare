package dev.veilshare.core.transfer

import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.identity.SharingIdentityManager
import dev.veilshare.core.identity.SharingPresence
import dev.veilshare.core.identity.SharingPresenceManager
import dev.veilshare.core.identity.SharingPublicIdentity
import dev.veilshare.core.identity.SharingRegistrationFactory
import dev.veilshare.core.model.UnregisterRequest
import dev.veilshare.core.platform.SignalingClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveSharingPresence(
    val identity: SharingPublicIdentity,
    val presence: SharingPresence,
)

/**
 * Serializes client-side presence lifecycle for one process.
 *
 * ReferenceCode rotation is intentionally fail-closed:
 * 1. revoke every currently registered route for this local identity;
 * 2. rotate the local random ReferenceCode;
 * 3. register the replacement.
 *
 * If step 3 fails the peer is temporarily offline, but the previous route has already
 * been revoked. A later ensureRegistered() retries the current local route.
 */
class SharingPresenceLifecycle(
    private val signalingClient: SignalingClient,
    private val identities: SharingIdentityManager,
    private val presence: SharingPresenceManager,
) {
    private val mutex = Mutex()

    suspend fun ensureRegistered(contextId: SharingContextId): ActiveSharingPresence = mutex.withLock {
        val handle = identities.getOrCreate(contextId)
        try {
            val identity = handle.publicIdentity
            val current = presence.getOrCreate(contextId)
            signalingClient.register(SharingRegistrationFactory.create(identity, current.referenceCode))
            ActiveSharingPresence(identity, current)
        } finally {
            handle.close()
        }
    }

    suspend fun rotateAndRegister(contextId: SharingContextId): ActiveSharingPresence = mutex.withLock {
        val handle = identities.getOrCreate(contextId)
        try {
            val identity = handle.publicIdentity

            // The server UNREGISTER contract removes every presence owned by this identity
            // on the current connection. Never rotate locally if revocation could not be
            // confirmed; that would risk multiple concurrently valid routes.
            signalingClient.unregister(UnregisterRequest(identity.identityId))

            val replacement = presence.rotate(contextId)
            signalingClient.register(SharingRegistrationFactory.create(identity, replacement.referenceCode))
            ActiveSharingPresence(identity, replacement)
        } finally {
            handle.close()
        }
    }

    /**
     * Stops network presence but deliberately preserves the local ReferenceCode so a later
     * ensureRegistered() can restore the same route. Local deletion/rotation is separate.
     */
    suspend fun deactivate(contextId: SharingContextId): Boolean = mutex.withLock {
        val identity = identities.publicIdentity(contextId) ?: return@withLock false
        signalingClient.unregister(UnregisterRequest(identity.identityId))
        true
    }
}
