package dev.veilshare.signaling

import dev.veilshare.core.model.ConnectionId
import dev.veilshare.core.model.SessionId

enum class RelaySessionState {
    PENDING,
    ACTIVE,
    CLOSED,
}

data class RelaySession(
    val sessionId: SessionId,
    val senderConnectionId: ConnectionId,
    val receiverConnectionId: ConnectionId,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val state: RelaySessionState,
)

class SessionRegistry(
    private val clock: SignalingClock,
    private val limits: SignalingLimits = SignalingLimits(),
) {
    private val sessions = linkedMapOf<SessionId, RelaySession>()

    fun create(
        sessionId: SessionId,
        senderConnectionId: ConnectionId,
        receiverConnectionId: ConnectionId,
    ): RelaySession {
        cleanupExpired()
        require(sessionId !in sessions) { "Duplicate session" }
        require(sessions.size < limits.maxPendingSessions) { "Session registry full" }
        val now = clock.nowMillis()
        val session = RelaySession(
            sessionId = sessionId,
            senderConnectionId = senderConnectionId,
            receiverConnectionId = receiverConnectionId,
            createdAtMillis = now,
            expiresAtMillis = now + limits.sessionTtlMillis,
            state = RelaySessionState.PENDING,
        )
        sessions[sessionId] = session
        return session
    }

    fun activate(sessionId: SessionId): RelaySession {
        val session = requireNotNull(lookup(sessionId)) { "Session not found" }
        val active = session.copy(state = RelaySessionState.ACTIVE)
        sessions[sessionId] = active
        return active
    }

    fun lookup(sessionId: SessionId): RelaySession? {
        cleanupExpired()
        return sessions[sessionId]
    }

    fun close(sessionId: SessionId): Boolean = sessions.remove(sessionId) != null

    fun closeConnection(connectionId: ConnectionId): Int {
        val toRemove = sessions.values
            .filter { it.senderConnectionId == connectionId || it.receiverConnectionId == connectionId }
            .map { it.sessionId }
        toRemove.forEach { sessions.remove(it) }
        return toRemove.size
    }

    fun cleanupExpired(): Int {
        val now = clock.nowMillis()
        val expired = sessions.values.filter { it.expiresAtMillis <= now }.map { it.sessionId }
        expired.forEach { sessions.remove(it) }
        return expired.size
    }

    fun size(): Int {
        cleanupExpired()
        return sessions.size
    }
}
