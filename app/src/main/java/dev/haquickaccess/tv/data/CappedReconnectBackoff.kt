package dev.haquickaccess.tv.data

/** Exponential reconnect delays with a bounded maximum and an explicit reset after a good auth. */
class CappedReconnectBackoff(
    private val initialDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 30_000L,
) {
    init {
        require(initialDelayMs > 0) { "Initial reconnect delay must be positive" }
        require(maximumDelayMs >= initialDelayMs) { "Reconnect cap must not be below the initial delay" }
    }

    private var nextDelayMs = initialDelayMs

    fun nextDelay(): Long = nextDelayMs.also {
        nextDelayMs = (nextDelayMs * 2).coerceAtMost(maximumDelayMs)
    }

    fun reset() {
        nextDelayMs = initialDelayMs
    }
}
