package com.jarvis.assistant.quotex.agent

/** Section 17: pure timing helpers. A countdown describes the clock only - it predicts nothing about the next candle. */
object CandleClock {
    fun remainingMs(nowMs: Long, candleMs: Long): Long {
        if (candleMs <= 0L) return 0L
        return candleMs - Math.floorMod(nowMs, candleMs)
    }

    fun format(ms: Long): String {
        val total = maxOf(0L, ms) / 1000L
        val m = total / 60L
        val s = total % 60L
        return (if (m < 10) "0$m" else "$m") + ":" + (if (s < 10) "0$s" else "$s")
    }

    fun label(candleSeconds: Int): String = when {
        candleSeconds < 60 -> "${candleSeconds}S"
        candleSeconds % 3600 == 0 -> "${candleSeconds / 3600}H"
        else -> "${candleSeconds / 60}M"
    }

    fun signalAgeSeconds(createdMs: Long, nowMs: Long): Long = maxOf(0L, (nowMs - createdMs) / 1000L)
}
