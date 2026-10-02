package com.jarvis.assistant.quotex.domain

/** Section 4: the chart timeframes the analyst supports. Legacy sub-minute candles (10s/15s/30s) still work via [TimeframePlan.forEntry]. */
enum class Timeframe(val seconds: Int, val label: String) {
    M1(60, "1M"), M5(300, "5M"), M15(900, "15M"), M30(1800, "30M"), H1(3600, "1H");

    companion object {
        fun fromSeconds(seconds: Int): Timeframe? = values().firstOrNull { it.seconds == seconds }
        val supportedSeconds: List<Int> get() = values().map { it.seconds }
    }
}

/**
 * Which timeframes are used for what: the entry timeframe is the one the user trades, [middleSeconds] confirms
 * momentum and [higherSeconds] gives context (section 4). Both are built by resampling the entry candles, so
 * they are exact multiples; a null level simply does not exist for that entry timeframe (nothing is invented).
 */
data class TimeframePlan(val entrySeconds: Int, val middleSeconds: Int?, val higherSeconds: Int?) {
    val middleFactor: Int? get() = factor(middleSeconds)
    val higherFactor: Int? get() = factor(higherSeconds)

    private fun factor(other: Int?): Int? =
        other?.takeIf { entrySeconds > 0 && it > entrySeconds && it % entrySeconds == 0 }?.let { it / entrySeconds }

    fun describe(): String {
        fun l(s: Int?) = s?.let { com.jarvis.assistant.quotex.agent.CandleClock.label(it) } ?: "-"
        return "entry ${l(entrySeconds)} / middle ${l(middleSeconds)} / higher ${l(higherSeconds)}"
    }

    companion object {
        fun forEntry(entrySeconds: Int): TimeframePlan = when (entrySeconds) {
            60 -> TimeframePlan(60, 300, 900)
            300 -> TimeframePlan(300, 900, 3600)
            900 -> TimeframePlan(900, 1800, 3600)
            1800 -> TimeframePlan(1800, 3600, null)
            3600 -> TimeframePlan(3600, null, null)
            else -> TimeframePlan(entrySeconds, entrySeconds * 4, entrySeconds * 12) // legacy x4 / x12 behaviour
        }
    }
}
