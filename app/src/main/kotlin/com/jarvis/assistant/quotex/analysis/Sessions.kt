package com.jarvis.assistant.quotex.analysis

/** Section 23. Hours are UTC. These are conventional windows, not exact exchange hours. */
enum class TradingSession(val label: String) {
    ASIAN("Asian"), LONDON("London"), LONDON_NEW_YORK_OVERLAP("London/New York overlap"),
    NEW_YORK("New York"), OFF_HOURS("Off hours")
}

object SessionClock {
    fun sessionAt(epochMs: Long): TradingSession {
        val hour = (((epochMs / 3_600_000L) % 24L) + 24L) % 24L
        return when (hour.toInt()) {
            in 0..6 -> TradingSession.ASIAN
            in 7..11 -> TradingSession.LONDON
            in 12..15 -> TradingSession.LONDON_NEW_YORK_OVERLAP
            in 16..20 -> TradingSession.NEW_YORK
            else -> TradingSession.OFF_HOURS
        }
    }

    /** 0 = Sunday .. 6 = Saturday (epoch day 0 was a Thursday). */
    fun dayOfWeek(epochMs: Long): Int {
        val days = Math.floorDiv(epochMs, 86_400_000L)
        return (((days + 4L) % 7L) + 7L).toInt() % 7
    }

    fun isWeekend(epochMs: Long): Boolean {
        val d = dayOfWeek(epochMs)
        return d == 0 || d == 6
    }
}
