package com.jarvis.assistant.quotex.analysis

/** Section 17. A countdown is timing information only - it does not predict the next candle. */
object CandleTimer {
    fun remainingMs(lastCandleOpenMs: Long, candleMs: Long, nowMs: Long): Long =
        ((lastCandleOpenMs + candleMs) - nowMs).coerceIn(0L, candleMs)

    fun format(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    fun signalAgeSeconds(signalTimeMs: Long, nowMs: Long): Long = ((nowMs - signalTimeMs) / 1000L).coerceAtLeast(0L)
}
