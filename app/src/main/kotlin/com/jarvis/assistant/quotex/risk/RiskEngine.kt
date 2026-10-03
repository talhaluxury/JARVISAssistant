package com.jarvis.assistant.quotex.risk

import java.util.TimeZone

enum class RiskState { ACTIVE, PAUSED_DAILY_LOSS, PAUSED_CONSECUTIVE_LOSSES, PAUSED_TRADE_FREQUENCY, PAUSED_EXPOSURE_LIMIT, PAUSED_BY_USER }

/**
 * User-declared numbers only - never inferred, never auto-adjusted. [stakePerTrade] and [dailyLossLimit]
 * are in the same currency/units the user thinks in (e.g. dollars, or "units" if they prefer); JARVIS never
 * changes [stakePerTrade] itself, so martingale-style doubling after a loss is structurally impossible here.
 */
data class RiskConfig(
    val stakePerTrade: Double = 1.0,
    val dailyLossLimit: Double = 5.0,
    val maxConsecutiveLosses: Int = 3,
    /** Trade-frequency limits (section 25): stops over-trading. Counted from setups shown as taken, never auto-adjusted. */
    val maxTradesPerDay: Int = 30,
    val maxTradesPerHour: Int = 10,
    /** Most total stake the user is willing to put at risk in one day (stake x trades), in the same units as [stakePerTrade]. */
    val maxDailyExposure: Double = 50.0
) {
    init {
        require(stakePerTrade > 0.0) { "stakePerTrade must be positive" }
        require(dailyLossLimit > 0.0) { "dailyLossLimit must be positive" }
        require(maxConsecutiveLosses >= 1) { "maxConsecutiveLosses must be at least 1" }
        require(maxTradesPerDay >= 1 && maxTradesPerHour >= 1) { "trade limits must be at least 1" }
        require(maxDailyExposure > 0.0) { "maxDailyExposure must be positive" }
    }
}

data class RiskSnapshot(
    val state: RiskState,
    val dailyPnL: Double,
    val consecutiveLosses: Int,
    val tradesToday: Int,
    val winsToday: Int,
    val lossesToday: Int,
    val config: RiskConfig,
    val tradesLastHour: Int = 0,
    val exposureToday: Double = 0.0
) {
    val paused: Boolean get() = state != RiskState.ACTIVE
    val reason: String? get() = when (state) {
        RiskState.PAUSED_DAILY_LOSS -> "Daily loss limit reached (P/L ${round2(dailyPnL)} vs limit -${round2(config.dailyLossLimit)})."
        RiskState.PAUSED_CONSECUTIVE_LOSSES -> "$consecutiveLosses losses in a row (limit ${config.maxConsecutiveLosses})."
        RiskState.PAUSED_TRADE_FREQUENCY -> "Too many trades: $tradesToday today (limit ${config.maxTradesPerDay}), $tradesLastHour in the last hour (limit ${config.maxTradesPerHour})."
        RiskState.PAUSED_EXPOSURE_LIMIT -> "Daily exposure limit reached (${round2(exposureToday)} of ${round2(config.maxDailyExposure)} staked)."
        RiskState.PAUSED_BY_USER -> "Paused by you."
        RiskState.ACTIVE -> null
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
}

/**
 * A discipline layer, not an execution layer (section 25/33): JARVIS never places, prepares or confirms a
 * real trade, so this can only ever pause JARVIS's own displayed setups - never the user's account, and
 * never the platform's own controls, which the user should still rely on directly. It never recommends
 * martingale and never changes the stake based on the previous result - [record] takes the fixed
 * configured stake every time, win or lose. A human can always override with [setUserPaused].
 */
class RiskEngine(
    private var config: RiskConfig = RiskConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var dayKey: Long = dayKeyFor(clock())
    private var dailyPnL = 0.0
    private var consecutiveLosses = 0
    private var tradesToday = 0
    private var winsToday = 0
    private var lossesToday = 0
    private var userPaused = false
    private val tradeTimes = java.util.ArrayDeque<Long>()

    fun updateConfig(newConfig: RiskConfig) {
        config = newConfig
    }

    fun currentConfig(): RiskConfig = config

    /**
     * Resuming from a manual pause is an explicit human decision, so it also acknowledges a consecutive-loss
     * cool-down (the streak resets). It does NOT lift a daily-loss pause - that lifts on the next day, or
     * when the user deliberately raises the limit themselves.
     */
    fun setUserPaused(paused: Boolean) {
        userPaused = paused
        if (!paused && consecutiveLosses >= config.maxConsecutiveLosses) consecutiveLosses = 0
    }

    fun isUserPaused(): Boolean = userPaused

    /**
     * Call once per resolved outcome, with the fixed configured stake - never a stake chosen by this call.
     * While paused, outcomes are NOT recorded: setups the user is not being shown are not trades they took,
     * so counting them would let hidden results drift the P/L or quietly lift a daily-loss pause.
     */
    fun record(won: Boolean, payoutFraction: Double) {
        rolloverIfNewDay()
        if (snapshot().paused) return
        tradesToday++
        tradeTimes.addLast(clock())
        if (won) {
            winsToday++
            consecutiveLosses = 0
            dailyPnL += config.stakePerTrade * payoutFraction
        } else {
            lossesToday++
            consecutiveLosses++
            dailyPnL -= config.stakePerTrade
        }
    }

    fun snapshot(): RiskSnapshot {
        rolloverIfNewDay()
        val cutoff = clock() - 3_600_000L
        while (tradeTimes.isNotEmpty() && tradeTimes.first() < cutoff) tradeTimes.removeFirst()
        val lastHour = tradeTimes.size
        val exposure = tradesToday * config.stakePerTrade
        val state = when {
            userPaused -> RiskState.PAUSED_BY_USER
            dailyPnL <= -config.dailyLossLimit -> RiskState.PAUSED_DAILY_LOSS
            consecutiveLosses >= config.maxConsecutiveLosses -> RiskState.PAUSED_CONSECUTIVE_LOSSES
            tradesToday >= config.maxTradesPerDay || lastHour >= config.maxTradesPerHour -> RiskState.PAUSED_TRADE_FREQUENCY
            exposure >= config.maxDailyExposure -> RiskState.PAUSED_EXPOSURE_LIMIT
            else -> RiskState.ACTIVE
        }
        return RiskSnapshot(state, dailyPnL, consecutiveLosses, tradesToday, winsToday, lossesToday, config, lastHour, exposure)
    }

    /** Only the daily counters roll over at day change; a losing streak spanning midnight still counts. */
    private fun rolloverIfNewDay() {
        val key = dayKeyFor(clock())
        if (key != dayKey) {
            dayKey = key
            dailyPnL = 0.0
            tradesToday = 0
            winsToday = 0
            lossesToday = 0
            tradeTimes.clear()
        }
    }

    companion object {
        fun dayKeyFor(epochMs: Long, zoneOffsetMs: Long = TimeZone.getDefault().rawOffset.toLong()): Long =
            Math.floorDiv(epochMs + zoneOffsetMs, 86_400_000L)
    }
}
