package dev.veilshare.signaling

data class SignalingLimits(
    val presenceTtlMillis: Long = 2 * 60 * 1000,
    val sessionTtlMillis: Long = 5 * 60 * 1000,
    val maxRegistrationsPerConnection: Int = 4,
    val maxPresenceEntries: Int = 10_000,
    val maxPendingSessions: Int = 10_000,
    val lookupWindowMillis: Long = 60 * 1000,
    val maxLookupsPerWindow: Int = 120,
    val sessionCreateWindowMillis: Long = 60 * 1000,
    val maxSessionCreatesPerWindow: Int = 60,
) {
    init {
        require(presenceTtlMillis > 0)
        require(sessionTtlMillis > 0)
        require(maxRegistrationsPerConnection > 0)
        require(maxPresenceEntries > 0)
        require(maxPendingSessions > 0)
        require(lookupWindowMillis > 0 && maxLookupsPerWindow > 0)
        require(sessionCreateWindowMillis > 0 && maxSessionCreatesPerWindow > 0)
    }
}

fun interface SignalingClock {
    fun nowMillis(): Long
}
