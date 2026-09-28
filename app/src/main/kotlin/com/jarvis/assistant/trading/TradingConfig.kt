package com.jarvis.assistant.trading

import kotlinx.serialization.Serializable

/**
 * PHASE 1 — CONFIGURATION
 *
 * All limits here are HARD ceilings enforced by the (later-phase) RiskManagementEngine, never
 * suggestions the signal/AI layer can reason its way past (spec §8, §22 — "never allow an
 * AI-generated signal to override these limits"). This class holds values only; it does not
 * enforce anything itself.
 *
 * `tradingMode` defaults to DEMO and there is deliberately no constructor path that starts the
 * app in LIVE — flipping it requires an explicit settings action plus (in a later phase) the
 * voice/UI confirmation flow from spec §16 and §36.
 *
 * `@Serializable` (Phase 8): these are persisted as JSON via TradingSettingsPersistence in the
 * data layer, so demo/live mode, the watchlist, and risk limits survive an app restart instead
 * of silently resetting to defaults every time the process dies.
 */
@Serializable
data class RiskSettings(
    val maxRiskPerTradePercent: Double = 0.5,
    val maxDailyLossPercent: Double = 2.0,
    val maxWeeklyLossPercent: Double = 6.0,
    val maxOpenTrades: Int = 3,
    val maxExposurePerCurrencyPercent: Double = 4.0,
    val maxSpreadPips: Double = 3.0,
    val maxLotSize: Double = 1.0,
    val minRiskRewardRatio: Double = 1.5,
    val maxConsecutiveLosses: Int = 3,
    val cooldownAfterLossMinutes: Int = 30,
    val staleDataThresholdSeconds: Int = 30
) {
    init {
        require(maxRiskPerTradePercent > 0) { "maxRiskPerTradePercent must be positive" }
        require(maxDailyLossPercent > 0) { "maxDailyLossPercent must be positive" }
        require(minRiskRewardRatio > 0) { "minRiskRewardRatio must be positive" }
        require(maxOpenTrades > 0) { "maxOpenTrades must be at least 1" }
    }
}

@Serializable
enum class TradingSession { SYDNEY, TOKYO, LONDON, NEW_YORK }

@Serializable
data class TradingSettings(
    val tradingMode: TradingMode = TradingMode.DEMO,
    val watchlist: Set<CurrencyPair> = setOf(
        CurrencyPair.EURUSD, CurrencyPair.GBPUSD, CurrencyPair.USDJPY, CurrencyPair.USDCHF,
        CurrencyPair.AUDUSD, CurrencyPair.USDCAD, CurrencyPair.NZDUSD, CurrencyPair.XAUUSD
    ),
    val allowedSessions: Set<TradingSession> = TradingSession.entries.toSet(),
    val confluenceStack: List<Timeframe> = Timeframe.DEFAULT_CONFLUENCE_STACK,
    val minConfidenceScoreToTrade: Int = 70,
    val risk: RiskSettings = RiskSettings(),
    /** Spec §36: an optional escape hatch from per-trade confirmation, but risk limits above
     * still apply unconditionally even when this is true — it never widens what RiskSettings
     * allows, only skips the "execute this trade?" prompt. */
    val preAuthorizedExecution: Boolean = false
) {
    init {
        require(watchlist.isNotEmpty()) { "watchlist must not be empty" }
        require(minConfidenceScoreToTrade in 0..100)
    }

    val isLive: Boolean get() = tradingMode == TradingMode.LIVE
}

/**
 * Global kill switch (spec §18/§20/§38). This is checked by every later engine (signal,
 * execution, scanner) before doing anything — a single source of truth so "is trading currently
 * allowed at all" can never be answered inconsistently across modules.
 *
 * Deliberately holds only state + the reason for that state, no side effects — wiring this into
 * a persisted store (so a process restart doesn't silently clear an emergency stop) is a later
 * phase, but the shape is fixed now so nothing built on top of it needs to change later.
 */
data class TradingLockState(
    val emergencyStopActive: Boolean = false,
    val dailyLossLockActive: Boolean = false,
    val cooldownUntilEpochMillis: Long? = null,
    val consecutiveLosses: Int = 0,
    val lockReason: String? = null
) {
    fun isTradingAllowed(nowEpochMillis: Long): Boolean =
        !emergencyStopActive &&
            !dailyLossLockActive &&
            (cooldownUntilEpochMillis == null || nowEpochMillis >= cooldownUntilEpochMillis)
}
