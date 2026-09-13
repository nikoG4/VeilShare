package dev.veilshare.core.transfer

import dev.veilshare.core.model.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

object HandshakeReplayPolicy {
    const val SEEN_SESSION_TTL_MS = 24L * 60L * 60L * 1000L
    const val MAX_SEEN_SESSIONS = 4_096
}

enum class HandshakeSessionClaim {
    CLAIMED,
    REPLAY,
    CAPACITY_EXCEEDED,
}

private object MonotonicHandshakeClock : TransferClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Process-local replay memory for already authenticated inbound SessionIds.
 *
 * Claim this only after HELLO identity/signature verification. This prevents arbitrary
 * unknown traffic from filling the cache while ensuring a captured valid HELLO cannot
 * create multiple handshakes or redirect repeated CONFIRM messages during the TTL.
 *
 * The cache is intentionally bounded. When full it fails closed for new sessions instead
 * of evicting a still-valid replay marker early.
 */
class HandshakeReplayGuard(
    private val clock: TransferClock = MonotonicHandshakeClock,
    private val ttlMs: Long = HandshakeReplayPolicy.SEEN_SESSION_TTL_MS,
    private val maxEntries: Int = HandshakeReplayPolicy.MAX_SEEN_SESSIONS,
) {
    private val mutex = Mutex()
    private val seen = linkedMapOf<String, Long>()

    init {
        require(ttlMs > 0) { "Handshake replay TTL must be positive" }
        require(maxEntries > 0) { "Handshake replay cache size must be positive" }
    }

    suspend fun claim(sessionId: SessionId): HandshakeSessionClaim = mutex.withLock {
        val now = clock.nowMillis()
        cleanupExpiredLocked(now)
        if (seen.containsKey(sessionId.value)) return@withLock HandshakeSessionClaim.REPLAY
        if (seen.size >= maxEntries) return@withLock HandshakeSessionClaim.CAPACITY_EXCEEDED
        seen[sessionId.value] = now
        HandshakeSessionClaim.CLAIMED
    }

    suspend fun contains(sessionId: SessionId): Boolean = mutex.withLock {
        val now = clock.nowMillis()
        cleanupExpiredLocked(now)
        seen.containsKey(sessionId.value)
    }

    suspend fun size(): Int = mutex.withLock {
        val now = clock.nowMillis()
        cleanupExpiredLocked(now)
        seen.size
    }

    private fun cleanupExpiredLocked(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val (_, firstSeenAt) = iterator.next()
            if (now - firstSeenAt >= ttlMs) iterator.remove()
        }
    }
}
