package com.jarvis.assistant.trading

import kotlin.math.abs

/**
 * PHASE 5 — PAPER TRADING ENGINE (spec §28: "paper mode must behave like live mode except no
 * real order is submitted")
 *
 * This is deliberately the SAME code path live trading will use later — it calls the real
 * [SignalConfidenceEngine], the real [RiskManagementEngine], and submits a real [OrderRequest]
 * through the real [BrokerAdapter] interface. The only thing that makes this "paper" is which
 * concrete adapter is wired in, which is why the constructor hard-refuses anything but
 * [TradingMode.DEMO] — this class must never become the thing that accidentally lets a live
 * adapter slip through untested.
 *
 * Split into two entry points for the same reason earlier engines were split:
 *  - [evaluate] takes already-fetched market data and is fully unit-testable without randomness.
 *  - [scan] is the live convenience wrapper that actually pulls from [MarketDataService] /
 *    [MultiTimeframeEngine] — thin, and not separately unit tested for that reason (it has no
 *    decision logic of its own beyond fetching and delegating to [evaluate]).
 */
class PaperTradingEngine(
    private val marketDataService: MarketDataService,
    private val multiTimeframeEngine: MultiTimeframeEngine,
    private val signalEngine: SignalConfidenceEngine,
    private val riskEngine: RiskManagementEngine,
    private val broker: BrokerAdapter,
    private val journal: TradeJournal,
    private val emergencyStop: EmergencyStopController
) {
    init {
        require(broker.mode == TradingMode.DEMO) {
            "PaperTradingEngine only accepts a DEMO-mode broker adapter — live trading is a separate, explicitly-authorized path (spec §16/§36)."
        }
    }

    private data class OpenPaperTrade(
        val clientOrderId: String,
        val pair: CurrencyPair,
        val direction: TradeDirection,
        val riskPercentOfEquity: Double,
        val entry: Double,
        val stopLoss: Double,
        val takeProfit: Double
    )

    private val openTrades = mutableMapOf<String, OpenPaperTrade>()
    private var dailyLossPercentSoFar = 0.0

    fun openPositionCount(): Int = openTrades.size
    fun currentDailyLossPercent(): Double = dailyLossPercentSoFar

    data class OpenPositionSummary(
        val pair: CurrencyPair, val direction: TradeDirection, val entry: Double,
        val stopLoss: Double, val takeProfit: Double, val riskPercentOfEquity: Double
    )

    /** Read-only snapshot of currently open paper positions — used by voice commands like
     * "show my open trades" and by the trading HUD, without exposing the mutable internal map. */
    fun openPositionsSummary(): List<OpenPositionSummary> = openTrades.values.map {
        OpenPositionSummary(it.pair, it.direction, it.entry, it.stopLoss, it.takeProfit, it.riskPercentOfEquity)
    }

    /** Full decision-to-execution pass for one pair, given data the caller already fetched.
     * Always produces and records exactly one [TradeJournalEntry] — including for WAIT/NO_TRADE,
     * per spec §22's "every decision must be logged," not only filled trades. */
    suspend fun evaluate(
        pair: CurrencyPair,
        mtfSnapshots: List<TimeframeStructureSnapshot>,
        entryCandles: List<Candle>,
        entryStructure: MarketStructureSnapshot,
        latestTick: PriceTick,
        tickQuality: DataQuality,
        settings: TradingSettings,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TradeJournalEntry {
        val signal = signalEngine.generate(pair, mtfSnapshots, entryCandles, entryStructure, latestTick, tickQuality, settings, nowEpochMillis)

        if (!signal.decision.isActionable) {
            val journalEntry = TradeJournalEntry.fromSignal(signal, ExecutionStatus.NOT_ATTEMPTED)
            journal.record(journalEntry)
            return journalEntry
        }

        if (!emergencyStop.isTradingAllowed(nowEpochMillis)) {
            val blockedEntry = TradeJournalEntry.fromSignal(
                signal, ExecutionStatus.RISK_BLOCKED,
                reasonOverride = emergencyStop.state.value.lockReason ?: "Trading is currently locked."
            )
            journal.record(blockedEntry)
            return blockedEntry
        }

        val account = broker.account()
        if (account == null) {
            val entry = TradeJournalEntry.fromSignal(signal, ExecutionStatus.UNKNOWN, reasonOverride = "Broker account unavailable — cannot risk-check.")
            journal.record(entry)
            return entry
        }

        val verdict = riskEngine.evaluate(
            signal = signal,
            account = account,
            currentPrice = latestTick.mid,
            openPositions = openTrades.values.map { OpenRiskPosition(it.pair, it.direction, it.riskPercentOfEquity) },
            lockState = emergencyStop.state.value,
            dailyLossPercentSoFar = dailyLossPercentSoFar,
            settings = settings,
            nowEpochMillis = nowEpochMillis
        )

        return when (verdict) {
            is RiskVerdict.Blocked -> {
                val entry = TradeJournalEntry.fromSignal(signal, ExecutionStatus.RISK_BLOCKED, reasonOverride = verdict.reason)
                journal.record(entry)
                entry
            }
            is RiskVerdict.Approved -> executeApprovedTrade(signal, verdict)
        }
    }

    private suspend fun executeApprovedTrade(signal: TradeSignal, verdict: RiskVerdict.Approved): TradeJournalEntry {
        // spec §13/§15: always verify the broker response, never assume success, never blindly retry.
        return when (val result = broker.submitOrder(verdict.orderRequest)) {
            is OrderResult.Filled -> {
                openTrades[verdict.orderRequest.clientOrderId] = OpenPaperTrade(
                    clientOrderId = verdict.orderRequest.clientOrderId,
                    pair = signal.pair,
                    direction = verdict.orderRequest.direction,
                    riskPercentOfEquity = verdict.riskPercentOfEquity,
                    entry = result.filledPrice,
                    stopLoss = verdict.orderRequest.stopLoss,
                    takeProfit = verdict.orderRequest.takeProfit
                )
                val entry = TradeJournalEntry.fromSignal(
                    signal, ExecutionStatus.FILLED,
                    riskPercentOfEquity = verdict.riskPercentOfEquity, positionSizeLots = verdict.orderRequest.lotSize
                ).copy(brokerOrderId = result.brokerOrderId, filledPrice = result.filledPrice)
                journal.record(entry)
                entry
            }
            is OrderResult.Rejected -> {
                val entry = TradeJournalEntry.fromSignal(signal, ExecutionStatus.REJECTED, reasonOverride = "Broker rejected order: ${result.reason}")
                journal.record(entry)
                entry
            }
            is OrderResult.Pending -> {
                val entry = TradeJournalEntry.fromSignal(signal, ExecutionStatus.PENDING).copy(brokerOrderId = result.brokerOrderId)
                journal.record(entry)
                entry
            }
            is OrderResult.Unknown -> {
                // spec §15: order status unknown -> record and stop, do not retry and do not
                // assume either fill or rejection.
                val entry = TradeJournalEntry.fromSignal(signal, ExecutionStatus.UNKNOWN, reasonOverride = "Order status unknown: ${result.reason}")
                journal.record(entry)
                entry
            }
        }
    }

    /** Checks every open paper trade against a fresh tick and closes (via the broker, never by
     * just deleting local state) any that have touched their stop-loss or take-profit. Feeds the
     * outcome back into [EmergencyStopController] (win/loss streak tracking) and the daily-loss
     * counter that [RiskManagementEngine] reads on the next [evaluate] call — this is what makes
     * the daily-loss lock and cooldown-after-losses rules actually activate during paper trading
     * rather than only existing on paper. [dailyLossLimitPercent] is passed in (rather than read
     * from a stored settings object) so the lock trigger always uses whatever limit is current. */
    suspend fun monitorOpenPositions(
        latestTicks: Map<CurrencyPair, PriceTick>,
        accountEquity: Double,
        dailyLossLimitPercent: Double,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<TradeJournalEntry> {
        val closedEntries = mutableListOf<TradeJournalEntry>()
        val candidates = openTrades.values.mapNotNull { trade ->
            val tick = latestTicks[trade.pair] ?: return@mapNotNull null
            val hitStop = if (trade.direction == TradeDirection.BUY) tick.mid <= trade.stopLoss else tick.mid >= trade.stopLoss
            val hitTarget = if (trade.direction == TradeDirection.BUY) tick.mid >= trade.takeProfit else tick.mid <= trade.takeProfit
            when {
                hitTarget -> trade to true
                hitStop -> trade to false
                else -> null
            }
        }

        for ((trade, isWin) in candidates) {
            val result = broker.closePosition(trade.clientOrderId)
            if (result !is OrderResult.Filled) continue // spec §15: unresolved close — leave it open rather than guess an outcome

            val risk = abs(trade.entry - trade.stopLoss)
            val reward = abs(trade.takeProfit - trade.entry)
            val rewardRiskMultiple = if (risk > 0) reward / risk else 0.0
            val pnlPercent = if (isWin) trade.riskPercentOfEquity * rewardRiskMultiple else -trade.riskPercentOfEquity
            val pnlAmount = accountEquity * pnlPercent / 100.0

            if (isWin) {
                emergencyStop.recordWin()
            } else {
                emergencyStop.recordLoss(nowEpochMillis)
                dailyLossPercentSoFar += trade.riskPercentOfEquity
                if (dailyLossPercentSoFar >= dailyLossLimitPercent) {
                    emergencyStop.triggerDailyLossLock(
                        "Daily loss limit of $dailyLossLimitPercent% reached (${"%.2f".format(dailyLossPercentSoFar)}%)."
                    )
                }
            }

            val updated = journal.update(trade.clientOrderId) { existing ->
                existing.copy(
                    executionStatus = ExecutionStatus.CLOSED,
                    exitPrice = result.filledPrice,
                    profitLoss = pnlAmount,
                    closedAtEpochMillis = nowEpochMillis
                )
            }
            if (updated != null) closedEntries += updated
            openTrades.remove(trade.clientOrderId)
        }
        return closedEntries
    }

    fun resetForNewTradingDay() {
        dailyLossPercentSoFar = 0.0
        emergencyStop.rolloverNewTradingDay()
    }

    private data class FetchedMarketData(
        val mtfSnapshots: List<TimeframeStructureSnapshot>,
        val entrySnapshot: TimeframeStructureSnapshot,
        val latestTick: PriceTick,
        val quality: DataQuality
    )

    private suspend fun fetchMarketData(pair: CurrencyPair, settings: TradingSettings, atrToleranceMultiplier: Double): FetchedMarketData {
        val mtfSnapshots = multiTimeframeEngine.analyzeStack(pair, settings.confluenceStack) { candles ->
            TechnicalIndicators.atr(candles).lastOrNull()?.times(atrToleranceMultiplier) ?: 0.0
        }
        val entryTimeframe = settings.confluenceStack.last()
        val entrySnapshot = mtfSnapshots.last { it.timeframe == entryTimeframe }
        val latestTick = broker.latestTick(pair)
        val quality = latestTick?.let { marketDataService.validate(it) } ?: DataQuality.Invalid("No price available for $pair.")
        val safeTick = latestTick ?: PriceTick(pair, 0.0, 0.0, 0L, DataSource.DEMO_SIMULATED)
        return FetchedMarketData(mtfSnapshots, entrySnapshot, safeTick, quality)
    }

    /** Live convenience wrapper for the AUTONOMOUS scanning loop (spec §15 "MODE 3") — fetches
     * data, generates a signal, and if it's approved, submits it immediately with no pause for
     * confirmation. Not unit tested directly (see class doc); its only logic is fetching and
     * delegating, which is covered indirectly by the [evaluate] test suite.
     *
     * NOT used for voice-triggered "buy/sell X" — see [requestManualTrade] for that path, which
     * stops short of submitting anything (spec §17: a voice command must never execute directly;
     * it must show the setup and wait for an explicit confirmation). */
    suspend fun scan(pair: CurrencyPair, settings: TradingSettings, atrToleranceMultiplier: Double = 0.5): TradeJournalEntry {
        val data = fetchMarketData(pair, settings, atrToleranceMultiplier)
        return evaluate(pair, data.mtfSnapshots, data.entrySnapshot.candles, data.entrySnapshot.structure, data.latestTick, data.quality, settings)
    }

    sealed class ManualTradeProposal {
        data class NotActionable(val signal: TradeSignal) : ManualTradeProposal()
        data class Blocked(val signal: TradeSignal, val reason: String) : ManualTradeProposal()
        data class ReadyToConfirm(val signal: TradeSignal, val verdict: RiskVerdict.Approved) : ManualTradeProposal()
    }

    /** spec §17 "Voice Safety" / §15 "MODE 2": analyzes [pair] and, if a real setup exists and
     * passes every risk check, returns [ManualTradeProposal.ReadyToConfirm] with the full trade
     * plan (entry/stop/target/risk) — WITHOUT submitting anything to the broker and WITHOUT
     * recording a journal entry. The caller (a voice command handler) is expected to speak the
     * plan back and ask for confirmation; only [confirmManualTrade] actually places the order. */
    suspend fun requestManualTrade(pair: CurrencyPair, settings: TradingSettings, nowEpochMillis: Long = System.currentTimeMillis()): ManualTradeProposal {
        val data = fetchMarketData(pair, settings, atrToleranceMultiplier = 0.5)
        val signal = signalEngine.generate(pair, data.mtfSnapshots, data.entrySnapshot.candles, data.entrySnapshot.structure, data.latestTick, data.quality, settings, nowEpochMillis)
        if (!signal.decision.isActionable) return ManualTradeProposal.NotActionable(signal)

        if (!emergencyStop.isTradingAllowed(nowEpochMillis)) {
            return ManualTradeProposal.Blocked(signal, emergencyStop.state.value.lockReason ?: "Trading is currently locked.")
        }
        val account = broker.account() ?: return ManualTradeProposal.Blocked(signal, "Broker account unavailable — cannot risk-check.")
        val verdict = riskEngine.evaluate(
            signal = signal, account = account, currentPrice = data.latestTick.mid,
            openPositions = openTrades.values.map { OpenRiskPosition(it.pair, it.direction, it.riskPercentOfEquity) },
            lockState = emergencyStop.state.value, dailyLossPercentSoFar = dailyLossPercentSoFar,
            settings = settings, nowEpochMillis = nowEpochMillis
        )
        return when (verdict) {
            is RiskVerdict.Approved -> ManualTradeProposal.ReadyToConfirm(signal, verdict)
            is RiskVerdict.Blocked -> ManualTradeProposal.Blocked(signal, verdict.reason)
        }
    }

    /** Actually submits the trade a prior [requestManualTrade] call proposed, after the user
     * said yes. Reuses the exact same execution path (and journal recording) as the autonomous
     * [evaluate]/[scan] flow — a manually-confirmed trade and an auto-approved one are recorded
     * identically once they reach this point. */
    suspend fun confirmManualTrade(signal: TradeSignal, verdict: RiskVerdict.Approved): TradeJournalEntry =
        executeApprovedTrade(signal, verdict)
}
