package com.jarvis.assistant.trading

import kotlin.math.max

/**
 * PHASE 6 — BACKTESTING ENGINE (spec §27)
 *
 * Deliberately reuses the exact same [MarketStructureEngine], [SignalConfidenceEngine], and
 * [RiskManagementEngine] instances the live/paper path uses — a backtest that runs different
 * logic than production isn't testing anything real. The only backtest-specific code here is the
 * historical-replay loop itself: feeding those engines a widening window of "data available so
 * far" bar by bar, exactly the discipline spec §29's "no look-ahead, no future-candle access"
 * demands of the ML layer too, even though this engine doesn't use ML.
 *
 * NO_TRADE / WAIT signals during the replay are simply not counted as trades — this engine
 * reports on what a strategy actually did, not on some resampled subset of only its winners.
 *
 * Known simplifications (documented, not hidden):
 *  - Single pair per run, so account-level exposure/max-open-trades checks in
 *    [RiskManagementEngine] never see more than one position — a portfolio-level backtest across
 *    correlated pairs is a further extension, not built here.
 *  - Entry fills at the next bar's open plus configured spread/slippage; exits at the exact
 *    stop-loss/take-profit price with no slippage modeled on the exit leg.
 *  - If a single bar's range touches BOTH the stop-loss and take-profit (a large enough bar),
 *    the stop-loss is assumed to have been hit first — OHLC data alone can't tell you the true
 *    intrabar path, and assuming the better outcome would make every result look rosier than
 *    reality could ever be.
 */

data class BacktestTrade(
    val signalId: String,
    val pair: CurrencyPair,
    val direction: TradeDirection,
    val entryTimeEpochMillis: Long,
    val entryPrice: Double,
    val exitTimeEpochMillis: Long,
    val exitPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val lotSize: Double,
    val riskPercentOfEquity: Double,
    val pnlAmount: Double,
    val exitReason: String
) {
    val isWin: Boolean get() = pnlAmount > 0.0
}

data class BacktestResult(
    val trades: List<BacktestTrade>,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePercent: Double,
    val profitFactor: Double?, // null when there are no losses to divide by
    val expectancy: Double,    // average P&L per trade, in account currency
    val maxDrawdownPercent: Double,
    val maxConsecutiveLosses: Int,
    val netPnl: Double,
    val endingBalance: Double
)

fun List<BacktestTrade>.toBacktestResult(startingBalance: Double): BacktestResult {
    val wins = filter { it.isWin }
    val losses = filter { !it.isWin }
    val netPnl = sumOf { it.pnlAmount }
    val grossWin = wins.sumOf { it.pnlAmount }
    val grossLoss = losses.sumOf { it.pnlAmount } // negative
    val profitFactor = if (grossLoss == 0.0) null else grossWin / -grossLoss
    val expectancy = if (isEmpty()) 0.0 else netPnl / size

    var equity = startingBalance
    var peak = startingBalance
    var maxDrawdownPercent = 0.0
    var consecutiveLosses = 0
    var maxConsecutiveLosses = 0
    // Trades are produced by the replay loop in chronological order already; sort defensively
    // in case a caller assembled this list from another source.
    sortedBy { it.exitTimeEpochMillis }.forEach { trade ->
        equity += trade.pnlAmount
        peak = max(peak, equity)
        if (peak > 0) maxDrawdownPercent = max(maxDrawdownPercent, (peak - equity) / peak * 100.0)
        if (trade.isWin) consecutiveLosses = 0 else {
            consecutiveLosses++
            maxConsecutiveLosses = max(maxConsecutiveLosses, consecutiveLosses)
        }
    }

    return BacktestResult(
        trades = this, totalTrades = size, wins = wins.size, losses = losses.size,
        winRatePercent = if (isEmpty()) 0.0 else wins.size.toDouble() / size * 100.0,
        profitFactor = profitFactor, expectancy = expectancy, maxDrawdownPercent = maxDrawdownPercent,
        maxConsecutiveLosses = maxConsecutiveLosses, netPnl = netPnl, endingBalance = startingBalance + netPnl
    )
}

data class BacktestConfig(
    val pair: CurrencyPair,
    /** Full stack, e.g. [D1, H4, H1, M15, M5] — last element is the entry timeframe. */
    val confluenceStack: List<Timeframe>,
    /** Pre-fetched, chronologically-sorted candle history per timeframe. Must extend at least
     * [warmupBars] entry-timeframe-equivalent bars before [backtestStartEpochMillis] so
     * indicators aren't computed on a cold start right at the boundary. */
    val historicalCandlesByTimeframe: Map<Timeframe, List<Candle>>,
    val backtestStartEpochMillis: Long,
    val backtestEndEpochMillis: Long,
    val startingBalance: Double = 10_000.0,
    val settings: TradingSettings,
    val spreadPips: Double = 1.5,
    val slippagePips: Double = 0.0,
    val warmupBars: Int = 60
)

class BacktestEngine(
    private val structureEngine: MarketStructureEngine = MarketStructureEngine(),
    private val signalEngine: SignalConfidenceEngine = SignalConfidenceEngine(),
    private val riskEngine: RiskManagementEngine = RiskManagementEngine()
) {

    private data class OpenPosition(
        val signalId: String,
        val direction: TradeDirection,
        val entryPrice: Double,
        val entryTimeEpochMillis: Long,
        val stopLoss: Double,
        val takeProfit: Double,
        val lotSize: Double,
        val riskPercentOfEquity: Double
    )

    fun run(config: BacktestConfig): BacktestResult {
        val entryTimeframe = config.confluenceStack.last()
        val entryCandles = config.historicalCandlesByTimeframe[entryTimeframe]
            ?: error("No historical candles supplied for entry timeframe ${entryTimeframe.label}.")
        require(entryCandles.size > config.warmupBars + 1) {
            "Not enough entry-timeframe history: need more than ${config.warmupBars + 1} bars, got ${entryCandles.size}."
        }

        val emergencyStop = EmergencyStopController { config.settings.risk }
        var equity = config.startingBalance
        var dailyLossAccumPercent = 0.0
        var lastDayBucket = entryCandles[config.warmupBars].openTimeEpochMillis / MILLIS_PER_DAY
        var openPosition: OpenPosition? = null
        val closedTrades = mutableListOf<BacktestTrade>()

        for (i in config.warmupBars until entryCandles.size) {
            val bar = entryCandles[i]
            if (bar.openTimeEpochMillis < config.backtestStartEpochMillis) continue
            if (bar.openTimeEpochMillis > config.backtestEndEpochMillis) break

            val dayBucket = bar.openTimeEpochMillis / MILLIS_PER_DAY
            if (dayBucket != lastDayBucket) {
                emergencyStop.rolloverNewTradingDay()
                dailyLossAccumPercent = 0.0
                lastDayBucket = dayBucket
            }

            val position = openPosition
            if (position != null) {
                val hitStop = if (position.direction == TradeDirection.BUY) bar.low <= position.stopLoss else bar.high >= position.stopLoss
                val hitTarget = if (position.direction == TradeDirection.BUY) bar.high >= position.takeProfit else bar.low <= position.takeProfit
                // Conservative tie-break — see class doc: assume the worse outcome when both are
                // touched in the same bar, since OHLC data can't reveal the true intrabar order.
                val exitLevel: Pair<Double, String>? = when {
                    hitStop -> position.stopLoss to "STOP_LOSS"
                    hitTarget -> position.takeProfit to "TAKE_PROFIT"
                    else -> null
                }
                if (exitLevel != null) {
                    val (exitPrice, reason) = exitLevel
                    val pnl = pnlForClose(config.pair, position, exitPrice)
                    equity += pnl
                    closedTrades += BacktestTrade(
                        signalId = position.signalId, pair = config.pair, direction = position.direction,
                        entryTimeEpochMillis = position.entryTimeEpochMillis, entryPrice = position.entryPrice,
                        exitTimeEpochMillis = bar.openTimeEpochMillis, exitPrice = exitPrice,
                        stopLoss = position.stopLoss, takeProfit = position.takeProfit, lotSize = position.lotSize,
                        riskPercentOfEquity = position.riskPercentOfEquity, pnlAmount = pnl, exitReason = reason
                    )
                    if (pnl > 0) emergencyStop.recordWin() else {
                        emergencyStop.recordLoss(bar.openTimeEpochMillis)
                        dailyLossAccumPercent += position.riskPercentOfEquity
                        if (dailyLossAccumPercent >= config.settings.risk.maxDailyLossPercent) {
                            emergencyStop.triggerDailyLossLock("Daily loss limit reached during backtest replay.")
                        }
                    }
                    openPosition = null
                }
                continue // never open a second position on the same bar we just managed one
            }

            if (i + 1 >= entryCandles.size) continue // no next bar available to fill a new signal on

            val entrySlice = entryCandles.subList(0, i + 1)
            val entryTolerance = TechnicalIndicators.atr(entrySlice).lastOrNull()?.times(0.5) ?: 0.0
            val entryStructure = structureEngine.analyze(entrySlice, entryTolerance)

            val mtfSnapshots = config.confluenceStack.map { tf ->
                val fullHistory = config.historicalCandlesByTimeframe[tf] ?: emptyList()
                val visible = fullHistory.filter { it.openTimeEpochMillis <= bar.openTimeEpochMillis }
                val tolerance = TechnicalIndicators.atr(visible).lastOrNull()?.times(0.5) ?: 0.0
                TimeframeStructureSnapshot(tf, structureEngine.analyze(visible, tolerance), visible)
            }

            val syntheticSpread = config.spreadPips * config.pair.pipSize
            val tick = PriceTick(
                pair = config.pair, bid = bar.close - syntheticSpread / 2, ask = bar.close + syntheticSpread / 2,
                timestampEpochMillis = bar.openTimeEpochMillis, source = DataSource.BROKER_HISTORICAL
            )

            val signal = signalEngine.generate(
                pair = config.pair, mtfSnapshots = mtfSnapshots, entryCandles = entrySlice,
                entryStructure = entryStructure, latestTick = tick, tickQuality = DataQuality.Valid,
                settings = config.settings, nowEpochMillis = bar.openTimeEpochMillis
            )

            if (!signal.decision.isActionable) continue

            val account = AccountSnapshot("BACKTEST", equity, equity, 0.0, equity, "USD")
            val verdict = riskEngine.evaluate(
                signal = signal, account = account, currentPrice = bar.close, openPositions = emptyList(),
                lockState = emergencyStop.state.value, dailyLossPercentSoFar = dailyLossAccumPercent,
                settings = config.settings, nowEpochMillis = bar.openTimeEpochMillis
            )
            if (verdict !is RiskVerdict.Approved) continue

            val fillBar = entryCandles[i + 1]
            val slippage = config.slippagePips * config.pair.pipSize
            val fillPrice = if (verdict.orderRequest.direction == TradeDirection.BUY) {
                fillBar.open + syntheticSpread / 2 + slippage
            } else {
                fillBar.open - syntheticSpread / 2 - slippage
            }

            openPosition = OpenPosition(
                signalId = signal.id, direction = verdict.orderRequest.direction, entryPrice = fillPrice,
                entryTimeEpochMillis = fillBar.openTimeEpochMillis, stopLoss = verdict.orderRequest.stopLoss,
                takeProfit = verdict.orderRequest.takeProfit, lotSize = verdict.orderRequest.lotSize,
                riskPercentOfEquity = verdict.riskPercentOfEquity
            )
        }

        // Any position still open at the end of the data window is force-closed at the last
        // known price rather than silently dropped — an unresolved trade is still a fact about
        // this run, not something to omit from the result.
        openPosition?.let { position ->
            val lastBar = entryCandles.last()
            val pnl = pnlForClose(config.pair, position, lastBar.close)
            closedTrades += BacktestTrade(
                signalId = position.signalId, pair = config.pair, direction = position.direction,
                entryTimeEpochMillis = position.entryTimeEpochMillis, entryPrice = position.entryPrice,
                exitTimeEpochMillis = lastBar.openTimeEpochMillis, exitPrice = lastBar.close,
                stopLoss = position.stopLoss, takeProfit = position.takeProfit, lotSize = position.lotSize,
                riskPercentOfEquity = position.riskPercentOfEquity, pnlAmount = pnl, exitReason = "END_OF_BACKTEST_DATA"
            )
        }

        return closedTrades.toBacktestResult(config.startingBalance)
    }

    /** Splits one config into two runs on either side of [splitEpochMillis] — spec §27's
     * "include out-of-sample testing." This engine has no parameters to fit in the first place
     * (it replays a fixed [TradingSettings], never tunes one), so there's no in-sample fitting
     * step to perform here; this helper exists so that whatever DOES tune settings (a human, or
     * a future optimizer) has a mechanical way to verify a chosen configuration on data it did
     * not have access to when it was chosen. */
    fun runInSampleAndOutOfSample(config: BacktestConfig, splitEpochMillis: Long): Pair<BacktestResult, BacktestResult> {
        val inSample = run(config.copy(backtestEndEpochMillis = splitEpochMillis))
        val outOfSample = run(config.copy(backtestStartEpochMillis = splitEpochMillis))
        return inSample to outOfSample
    }

    private fun pnlForClose(pair: CurrencyPair, position: OpenPosition, exitPrice: Double): Double {
        val direction = if (position.direction == TradeDirection.BUY) 1.0 else -1.0
        val pnlPips = (exitPrice - position.entryPrice) / pair.pipSize * direction
        val pipValue = PositionSizingEngine.pipValuePerLotInUsd(pair, exitPrice) ?: return 0.0
        return pnlPips * pipValue * position.lotSize
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
