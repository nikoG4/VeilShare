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

/**
 * Fixed-window limiter that caps both message count and cumulative cost.
 * Used for relay traffic so legitimate fragmentation can use many small frames without
 * permitting a connection to send unbounded data during the window.
 */
class FixedWindowBudgetLimiter(
    private val clock: SignalingClock,
    private val windowMillis: Long,
    private val maxEvents: Int,
    private val maxCost: Long,
) {
    private val windows = linkedMapOf<String, BudgetWindow>()

    init {
        require(windowMillis > 0)
        require(maxEvents > 0)
        require(maxCost > 0)
    }

    fun allow(key: String, cost: Long): Boolean {
        require(key.isNotBlank())
        require(cost >= 0)

        val now = clock.nowMillis()
        val current = windows[key]
        val window = if (current == null || now >= current.startedAtMillis + windowMillis) {
            BudgetWindow(now, eventCount = 0, cost = 0)
        } else {
            current
        }

        if (window.eventCount >= maxEvents) {
            windows[key] = window
            return false
        }
        if (cost > maxCost - window.cost) {
            windows[key] = window
            return false
        }

        windows[key] = window.copy(
            eventCount = window.eventCount + 1,
            cost = window.cost + cost,
        )
        return true
    }

    fun cleanupExpired(): Int {
        val now = clock.nowMillis()
        val expired = windows.filterValues { now >= it.startedAtMillis + windowMillis }.keys.toList()
        expired.forEach { windows.remove(it) }
        return expired.size
    }

    private data class BudgetWindow(
        val startedAtMillis: Long,
        val eventCount: Int,
        val cost: Long,
    )
}
