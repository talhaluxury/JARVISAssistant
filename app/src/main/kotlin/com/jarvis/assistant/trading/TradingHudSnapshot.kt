package com.jarvis.assistant.trading

/**
 * PHASE 7 — TRADING HUD DATA MODEL (spec §24)
 *
 * This is the data half of the "JARVIS TRADING CORE" HUD spec §24 describes — every field it
 * lists (market, status, trend, timeframe, signal, confidence, spread, risk, R:R,
 * entry/stop/target, broker connection, DEMO/LIVE) has a home here.
 *
 * Deliberately NOT a Compose screen: this project already has a working HUD renderer
 * (com.jarvis.assistant.hud.WallpaperEventBus / the live-wallpaper HUD), with its own visual
 * language, color tokens, and animation conventions that aren't visible from the trading package
 * alone. Bolting on a second, differently-styled full-screen dashboard without seeing those
 * conventions would produce something that visually clashes with the rest of the app rather than
 * feeling like part of it. What this phase DOES wire up: ForexBrainCommandExecutor pushes short
 * status lines through the existing WallpaperEventBus on every scan/signal/trade event (see its
 * pushHud calls), so the generic HUD already reflects trading activity today. Rendering this
 * richer snapshot as its own dedicated screen is the next step once someone can point at the
 * existing Compose HUD conventions to match.
 */
data class TradingHudSnapshot(
    val pair: CurrencyPair,
    val brokerConnected: Boolean,
    val tradingMode: TradingMode,
    val trend: MarketRegime?,
    val timeframe: Timeframe?,
    val decision: TradeDecision?,
    val confidenceScore: Int?,
    val spreadPips: Double?,
    val riskPercent: Double?,
    val riskRewardRatio: Double?,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val openPositionCount: Int,
    val emergencyStopActive: Boolean,
    val dailyLossLockActive: Boolean
) {
    /** One-line rendering for surfaces (like WallpaperEventBus) that only have room for a short
     * status string rather than the full structured snapshot. */
    fun toStatusLine(): String {
        val modeTag = if (tradingMode == TradingMode.LIVE) "LIVE" else "DEMO"
        val lockTag = when {
            emergencyStopActive -> " [EMERGENCY STOP]"
            dailyLossLockActive -> " [DAILY LOSS LOCK]"
            else -> ""
        }
        val decisionText = decision?.name ?: "SCANNING"
        val confidenceText = confidenceScore?.let { " $it/100" } ?: ""
        return "${pair.label} [$modeTag] $decisionText$confidenceText$lockTag"
    }

    companion object {
        fun from(
            pair: CurrencyPair,
            brokerConnected: Boolean,
            settings: TradingSettings,
            signal: TradeSignal?,
            entryStructure: MarketStructureSnapshot?,
            entryTimeframe: Timeframe?,
            latestSpreadPips: Double?,
            paperTradingEngine: PaperTradingEngine,
            lockState: TradingLockState
        ) = TradingHudSnapshot(
            pair = pair,
            brokerConnected = brokerConnected,
            tradingMode = settings.tradingMode,
            trend = entryStructure?.regime,
            timeframe = entryTimeframe,
            decision = signal?.decision,
            confidenceScore = signal?.confidenceScore,
            spreadPips = latestSpreadPips,
            riskPercent = settings.risk.maxRiskPerTradePercent,
            riskRewardRatio = signal?.riskRewardRatio,
            entry = signal?.entry,
            stopLoss = signal?.stopLoss,
            takeProfit = signal?.takeProfit,
            openPositionCount = paperTradingEngine.openPositionCount(),
            emergencyStopActive = lockState.emergencyStopActive,
            dailyLossLockActive = lockState.dailyLossLockActive
        )
    }
}
