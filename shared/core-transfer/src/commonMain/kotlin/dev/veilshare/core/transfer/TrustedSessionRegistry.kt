package dev.veilshare.core.transfer

import dev.veilshare.core.model.SessionConfirmAck
import dev.veilshare.core.model.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

object TrustedSessionRegistryPolicy {
    const val MAX_PENDING_HANDSHAKES = 16
    const val MAX_ESTABLISHED_SESSIONS = 8
    const val PENDING_HANDSHAKE_TIMEOUT_MS = 2L * 60L * 1000L
}

private object MonotonicTrustedSessionClock : TransferClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

sealed interface PendingRegistrationResult {
    data object Registered : PendingRegistrationResult
    data object Duplicate : PendingRegistrationResult
    data class CapacityExceeded(val maxPending: Int) : PendingRegistrationResult
}

/**
 * Process-local owner for pending inbound handshakes and established peer sessions.
 *
 * Pending X25519 private material is always closed when an entry expires, is replaced by
 * a terminal operation, or the registry is closed. Established session keys remain owned
 * by EstablishedPeerSession and are zeroized when removed/closed.
 */
class TrustedSessionRegistry(
    private val clock: TransferClock = MonotonicTrustedSessionClock,
    private val maxPending: Int = TrustedSessionRegistryPolicy.MAX_PENDING_HANDSHAKES,
    private val maxEstablished: Int = TrustedSessionRegistryPolicy.MAX_ESTABLISHED_SESSIONS,
    private val pendingTimeoutMs: Long = TrustedSessionRegistryPolicy.PENDING_HANDSHAKE_TIMEOUT_MS,
) : AutoCloseable {
    private data class PendingEntry(
        val handshake: PendingInboundHandshake,
        val createdAtMillis: Long,
    )

    private val mutex = Mutex()
    private val pending = linkedMapOf<String, PendingEntry>()
    private val established = linkedMapOf<String, EstablishedPeerSession>()
    private var closed = false

    init {
        require(maxPending > 0)
        require(maxEstablished > 0)
        require(pendingTimeoutMs > 0)
    }

    suspend fun registerPending(handshake: PendingInboundHandshake): PendingRegistrationResult = mutex.withLock {
        check(!closed) { "Trusted session registry is closed" }
        sweepExpiredLocked(clock.nowMillis())
        val key = handshake.sessionId.value
        if (pending.containsKey(key) || established.containsKey(key)) {
            handshake.close()
            return@withLock PendingRegistrationResult.Duplicate
        }
        if (pending.size >= maxPending) {
            handshake.close()
            return@withLock PendingRegistrationResult.CapacityExceeded(maxPending)
        }
        pending[key] = PendingEntry(handshake, clock.nowMillis())
        PendingRegistrationResult.Registered
    }

    suspend fun completeInbound(
        sessionId: SessionId,
        ack: SessionConfirmAck,
    ): EstablishedPeerSession = mutex.withLock {
        check(!closed) { "Trusted session registry is closed" }
        sweepExpiredLocked(clock.nowMillis())
        check(established.size < maxEstablished) { "Established session capacity exceeded" }
        val entry = pending.remove(sessionId.value)
            ?: throw IllegalStateException("Pending inbound handshake not found")
        val session = try {
            entry.handshake.complete(ack)
        } catch (failure: Throwable) {
            entry.handshake.close()
            throw failure
        }
        established[sessionId.value] = session
        session
    }

    suspend fun registerEstablished(session: EstablishedPeerSession) = mutex.withLock {
        check(!closed) { "Trusted session registry is closed" }
        sweepExpiredLocked(clock.nowMillis())
        val key = session.sessionId.value
        check(!pending.containsKey(key) && !established.containsKey(key)) { "Session already registered" }
        check(established.size < maxEstablished) { "Established session capacity exceeded" }
        established[key] = session
    }

    suspend fun getEstablished(sessionId: SessionId): EstablishedPeerSession? = mutex.withLock {
        check(!closed) { "Trusted session registry is closed" }
        sweepExpiredLocked(clock.nowMillis())
        established[sessionId.value]
    }

    suspend fun removeEstablished(sessionId: SessionId): Boolean = mutex.withLock {
        established.remove(sessionId.value)?.let {
            it.close()
            true
        } ?: false
    }

    suspend fun cancelPending(sessionId: SessionId): Boolean = mutex.withLock {
        pending.remove(sessionId.value)?.let {
            it.handshake.close()
            true
        } ?: false
    }

    suspend fun sweepExpired(): Int = mutex.withLock {
        sweepExpiredLocked(clock.nowMillis())
    }

    suspend fun pendingCount(): Int = mutex.withLock {
        sweepExpiredLocked(clock.nowMillis())
        pending.size
    }

    suspend fun establishedCount(): Int = mutex.withLock { established.size }

    private fun sweepExpiredLocked(now: Long): Int {
        var removed = 0
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (now - entry.createdAtMillis >= pendingTimeoutMs) {
                iterator.remove()
                entry.handshake.close()
                removed++
            }
        }
        return removed
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { it.handshake.close() }
        pending.clear()
        established.values.forEach { it.close() }
        established.clear()
    }
}
