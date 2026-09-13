package dev.veilshare.core.transfer

import dev.veilshare.core.identity.SharingContextId
import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionId

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
 * PendingInboundHandshake never escapes this coordinator. That keeps receiver X25519
 * private material under a bounded timeout registry rather than making UI responsible for
 * closing it. Established sessions are likewise registered centrally for explicit teardown.
 */
class ManagedTrustedSessionCoordinator(
    private val inboundResponder: TrustedInboundSessionResponder,
    private val registry: TrustedSessionRegistry,
) {
    suspend fun beginInbound(
        localContextId: SharingContextId,
        sessionId: SessionId,
        routedHello: RoutedHandshakeMessage,
    ): ManagedInboundBeginResult {
        return when (val result = inboundResponder.begin(localContextId, sessionId, routedHello)) {
            is InboundSessionBeginResult.UnknownPeer ->
                ManagedInboundBeginResult.UnknownPeer(result.result)
            is InboundSessionBeginResult.ReplayRejected ->
                ManagedInboundBeginResult.ReplayRejected(result.sessionId)
            is InboundSessionBeginResult.CapacityRejected ->
                ManagedInboundBeginResult.ReplayCapacityRejected(result.maxSeenSessions)
            is InboundSessionBeginResult.Pending -> {
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
