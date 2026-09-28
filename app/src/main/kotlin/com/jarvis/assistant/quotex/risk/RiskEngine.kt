package com.jarvis.assistant.quotex.risk

import java.util.TimeZone

enum class RiskState { ACTIVE, PAUSED_DAILY_LOSS, PAUSED_CONSECUTIVE_LOSSES, PAUSED_BY_USER }

/**
 * User-declared numbers only - never inferred, never auto-adjusted. [stakePerTrade] and [dailyLossLimit]
 * are in the same currency/units the user thinks in (e.g. dollars, or "units" if they prefer); JARVIS never
 * changes [stakePerTrade] itself, so martingale-style doubling after a loss is structurally impossible here.
 */
data class RiskConfig(
    val stakePerTrade: Double = 1.0,
    val dailyLossLimit: Double = 5.0,
    val maxConsecutiveLosses: Int = 3
) {
    init {
        require(stakePerTrade > 0.0) { "stakePerTrade must be positive" }
        require(dailyLossLimit > 0.0) { "dailyLossLimit must be positive" }
        require(maxConsecutiveLosses >= 1) { "maxConsecutiveLosses must be at least 1" }
    }
}

data class RiskSnapshot(
    val state: RiskState,
    val dailyPnL: Double,
    val consecutiveLosses: Int,
    val tradesToday: Int,
    val winsToday: Int,
    val lossesToday: Int,
    val config: RiskConfig
) {
    val paused: Boolean get() = state != RiskState.ACTIVE
    val reason: String? get() = when (state) {
        RiskState.PAUSED_DAILY_LOSS -> "Daily loss limit reached (P/L ${round2(dailyPnL)} vs limit -${round2(config.dailyLossLimit)})."
        RiskState.PAUSED_CONSECUTIVE_LOSSES -> "$consecutiveLosses losses in a row (limit ${config.maxConsecutiveLosses})."
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
        val state = when {
            userPaused -> RiskState.PAUSED_BY_USER
            dailyPnL <= -config.dailyLossLimit -> RiskState.PAUSED_DAILY_LOSS
            consecutiveLosses >= config.maxConsecutiveLosses -> RiskState.PAUSED_CONSECUTIVE_LOSSES
            else -> RiskState.ACTIVE
        }
        return RiskSnapshot(state, dailyPnL, consecutiveLosses, tradesToday, winsToday, lossesToday, config)
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
        }
    }

    companion object {
        fun dayKeyFor(epochMs: Long, zoneOffsetMs: Long = TimeZone.getDefault().rawOffset.toLong()): Long =
            Math.floorDiv(epochMs + zoneOffsetMs, 86_400_000L)
    }
}
