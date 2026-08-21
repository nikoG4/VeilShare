package dev.veilshare.signaling

class FixedWindowRateLimiter(
    private val clock: SignalingClock,
    private val windowMillis: Long,
    private val maxEvents: Int,
) {
    private val windows = linkedMapOf<String, Window>()

    init {
        require(windowMillis > 0)
        require(maxEvents > 0)
    }

    fun allow(key: String): Boolean {
        require(key.isNotBlank())
        val now = clock.nowMillis()
        val current = windows[key]
        val window = if (current == null || now >= current.startedAtMillis + windowMillis) {
            Window(now, 0)
        } else {
            current
        }
        if (window.count >= maxEvents) {
            windows[key] = window
            return false
        }
        windows[key] = window.copy(count = window.count + 1)
        return true
    }

    fun cleanupExpired(): Int {
        val now = clock.nowMillis()
        val expired = windows.filterValues { now >= it.startedAtMillis + windowMillis }.keys.toList()
        expired.forEach { windows.remove(it) }
        return expired.size
    }

    private data class Window(val startedAtMillis: Long, val count: Int)
}
