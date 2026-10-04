package com.jarvis.assistant.quotex.ocr

/**
 * Candle length from the broker's own countdown chip ("00:07" next to the live price). The largest countdown
 * value seen over at least one full candle IS the candle length, so it is exact and does not depend on how far
 * the chart is zoomed. Pure Kotlin, no Android types.
 */
class CountdownTimeframe(private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    private var max = 0
    // null = nothing seen yet (0 is a valid clock value, so it can't be the "unset" marker)
    private var firstAt: Long? = null

    fun reset() { max = 0; firstAt = null }

    /** Feed one reading's countdown (seconds, or null). Returns the standard timeframe once it is certain, else null. */
    fun add(countdownSec: Int?): Int? {
        if (countdownSec == null || countdownSec <= 0 || countdownSec > 3600) return result()
        val now = clockMs()
        if (firstAt == null) firstAt = now
        if (countdownSec > max) max = countdownSec
        return result()
    }

    private fun result(): Int? {
        if (max == 0) return null
        val tf = STANDARD.firstOrNull { it >= max } ?: return null
        // Certain only after a whole candle has passed (a new candle restarts the countdown at the full length).
        val observedMs = clockMs() - (firstAt ?: return null)
        return if (observedMs >= tf * 1200L) tf else null
    }

    companion object {
        val STANDARD = listOf(5, 10, 15, 30, 60, 120, 180, 300, 600, 900, 1800, 3600)
    }
}
