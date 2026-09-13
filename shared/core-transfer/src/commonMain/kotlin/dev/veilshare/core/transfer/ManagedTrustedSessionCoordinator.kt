package dev.veilshare.core.transfer

import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ManagedInboundBeginResult {
    data class Pending(val sessionId: SessionId) : ManagedInboundBeginResult
    data class UnknownPeer(val result: InboundHelloTrustResult.UnknownIdentity) : ManagedInboundBeginResult
    data class ReplayRejected(val sessionId: SessionId) : ManagedInboundBeginResult
    data class ReplayCapacityRejected(val maxSeenSessions: Int) : ManagedInboundBeginResult
    data class RegistryDuplicate(val sessionId: SessionId) : ManagedInboundBeginResult
    data class RegistryCapacityRejected(val maxPending: Int) : ManagedInboundBeginResult
}

/**
 * App-facing lifecycle wrapper for trusted handshakes.
 *
 * PendingInboundHandshake never escapes this coordinator. Receiver X25519 private material
 * is therefore always owned by the bounded timeout registry. beginInbound() is serialized
 * so admission is checked before SESSION_CONFIRM is allocated/sent; capacity rejection has
 * no network or ephemeral-key side effects.
 */
class ManagedTrustedSessionCoordinator(
    private val inboundResponder: TrustedInboundSessionResponder,
    private val registry: TrustedSessionRegistry,
) {
    private val beginMutex = Mutex()

    suspend fun beginInbound(
        localContextId: SharingContextId,
        sessionId: SessionId,
        routedHello: RoutedHandshakeMessage,
    ): ManagedInboundBeginResult = beginMutex.withLock {
        when (val admission = registry.inspectPendingAdmission(sessionId)) {
            PendingAdmissionResult.Duplicate ->
                return@withLock ManagedInboundBeginResult.RegistryDuplicate(sessionId)
            is PendingAdmissionResult.CapacityExceeded ->
                return@withLock ManagedInboundBeginResult.RegistryCapacityRejected(admission.maxPending)
            PendingAdmissionResult.Available -> Unit
        }

        when (val result = inboundResponder.begin(localContextId, sessionId, routedHello)) {
            is InboundSessionBeginResult.UnknownPeer ->
                ManagedInboundBeginResult.UnknownPeer(result.result)
            is InboundSessionBeginResult.ReplayRejected ->
                ManagedInboundBeginResult.ReplayRejected(result.sessionId)
            is InboundSessionBeginResult.CapacityRejected ->
                ManagedInboundBeginResult.ReplayCapacityRejected(result.maxSeenSessions)
            is InboundSessionBeginResult.Pending -> {
                // No other beginInbound call can race the preflight while beginMutex is held.
                // Keep the registry's own duplicate/capacity check as defense in depth.
                when (val registered = registry.registerPending(result.handshake)) {
                    PendingRegistrationResult.Registered -> ManagedInboundBeginResult.Pending(sessionId)
                    PendingRegistrationResult.Duplicate -> ManagedInboundBeginResult.RegistryDuplicate(sessionId)
                    is PendingRegistrationResult.CapacityExceeded ->
                        ManagedInboundBeginResult.RegistryCapacityRejected(registered.maxPending)
                }
            }
        }
    }

    suspend fun completeInbound(
        sessionId: SessionId,
        ack: SessionConfirmAck,
    ): EstablishedPeerSession = registry.completeInbound(sessionId, ack)

    suspend fun registerOutbound(completion: OutboundHandshakeCompletion): EstablishedPeerSession {
        registry.registerEstablished(completion.session)
        return completion.session
    }

    suspend fun getEstablished(sessionId: SessionId): EstablishedPeerSession? =
        registry.getEstablished(sessionId)

    suspend fun closeSession(sessionId: SessionId): Boolean =
        registry.removeEstablished(sessionId)

    suspend fun cancelPending(sessionId: SessionId): Boolean =
        registry.cancelPending(sessionId)

    suspend fun sweepExpiredPending(): Int =
        registry.sweepExpired()
}
