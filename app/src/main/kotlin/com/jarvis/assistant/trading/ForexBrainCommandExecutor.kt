package com.jarvis.assistant.trading

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.hud.JarvisHudState
import com.jarvis.assistant.hud.WallpaperEventBus

/**
 * PHASE 7 — FOREX VOICE COMMANDS (spec §16/§17/§18/§19/§20 across both spec documents)
 *
 * Mirrors com.jarvis.assistant.agent.BrainCommandExecutor's exact shape: a suspend `execute`
 * that returns the spoken response, or null for anything it doesn't own so the caller falls
 * through to the normal executor.
 *
 * The one rule every branch here respects: a voice command NEVER causes a trade to be submitted
 * on its own. [JarvisCommand.RequestForexTrade] only ever calls
 * [PaperTradingEngine.requestManualTrade], which stops short of touching the broker — actually
 * placing the order requires a separate, later [JarvisCommand.ConfirmForexTradeExecution]. This
 * is deliberately a different (safer) code path than [PaperTradingEngine.scan], which DOES
 * auto-execute and is reserved for an explicitly-authorized background scanning loop, never for
 * something a spoken sentence triggers directly.
 */
class ForexBrainCommandExecutor(
    private val settingsStore: TradingSettingsStore,
    private val paperTradingEngine: PaperTradingEngine,
    private val journal: TradeJournal,
    private val emergencyStop: EmergencyStopController,
    private val broker: BrokerAdapter
) {
    // Single-slot pending confirmation — mirrors the existing SendWhatsAppMessage/
    // SendPendingMessage pattern's single-pending-action assumption. A new RequestForexTrade
    // (or an emergency stop) always overwrites/clears whatever was pending before.
    private var pending: PaperTradingEngine.ManualTradeProposal.ReadyToConfirm? = null

    suspend fun execute(command: JarvisCommand): String? = when (command) {
        JarvisCommand.ScanForexMarket -> scanWatchlist()

        is JarvisCommand.AnalyzeForexPair -> {
            val pair = parsePair(command.pairSymbol)
            if (pair == null) "I don't recognize the pair \"${command.pairSymbol}\"."
            else {
                pushHud(pair, "SCANNING")
                describeProposal(pair, paperTradingEngine.requestManualTrade(pair, settingsStore.current()))
            }
        }

        JarvisCommand.ShowOpenForexTrades -> {
            val positions = paperTradingEngine.openPositionsSummary()
            if (positions.isEmpty()) "No open forex positions."
            else "Open positions:\n" + positions.joinToString("\n") {
                "${it.pair.label} ${it.direction} — entry ${it.entry}, stop ${it.stopLoss}, target ${it.takeProfit}, risking ${"%.2f".format(it.riskPercentOfEquity)}%."
            }
        }

        JarvisCommand.ShowForexRiskStatus -> {
            val lock = emergencyStop.state.value
            val settings = settingsStore.current()
            val equity = broker.account()?.equity
            buildString {
                append("Daily loss so far: ${"%.2f".format(paperTradingEngine.currentDailyLossPercent())}% of ${settings.risk.maxDailyLossPercent}% limit. ")
                append("Open trades: ${paperTradingEngine.openPositionCount()}/${settings.risk.maxOpenTrades}. ")
                append("Risk per trade: ${settings.risk.maxRiskPerTradePercent}%. ")
                if (equity != null) append("Account equity: ${"%.2f".format(equity)}. ")
                if (lock.emergencyStopActive) append("EMERGENCY STOP is active. ")
                if (lock.dailyLossLockActive) append("Daily loss lock is active. ")
                if (lock.cooldownUntilEpochMillis != null) append("Cooling down after ${lock.consecutiveLosses} consecutive losses. ")
            }.trim()
        }

        JarvisCommand.WhyNoForexTrade -> {
            val last = journal.all().filter { !it.decision.isActionable }.maxByOrNull { it.timestampEpochMillis }
            if (last == null) "I haven't analyzed any pair yet — nothing to explain."
            else "${last.pair.label}: ${last.decision} — ${last.reason}"
        }

        JarvisCommand.ShowForexPerformance -> {
            val a = journal.all().toAnalytics()
            if (a.totalClosedTrades == 0) "No closed trades yet."
            else buildString {
                val netPnl = a.averageWin * a.wins + a.averageLoss * a.losses
                appendLine("${a.totalClosedTrades} trades closed, ${a.wins} wins, ${a.losses} losses (${"%.1f".format(a.winRatePercent)}% win rate).")
                appendLine("Net P/L: ${"%.2f".format(netPnl)}. Max drawdown: ${"%.2f".format(a.maxDrawdown)}.")
                append("Profit factor: ${if (a.profitFactor.isInfinite()) "undefined (no losses yet)" else "%.2f".format(a.profitFactor)}.")
            }.trim()
        }

        is JarvisCommand.RequestForexTrade -> {
            val pair = parsePair(command.pairSymbol)
            if (pair == null) {
                "I don't recognize the pair \"${command.pairSymbol}\"."
            } else {
                pushHud(pair, "ANALYZING")
                when (val proposal = paperTradingEngine.requestManualTrade(pair, settingsStore.current())) {
                    is PaperTradingEngine.ManualTradeProposal.NotActionable ->
                        "No trade setup for ${pair.label} right now. ${proposal.signal.reason}"
                    is PaperTradingEngine.ManualTradeProposal.Blocked ->
                        "${pair.label} setup found but blocked: ${proposal.reason}"
                    is PaperTradingEngine.ManualTradeProposal.ReadyToConfirm -> {
                        pending = proposal
                        val mismatchNote = command.requestedDirection
                            ?.takeIf { it != proposal.signal.decision.direction?.name }
                            ?.let { "Note: you asked to $it, but the setup found is actually a ${proposal.signal.decision.direction}. " }
                            ?: ""
                        mismatchNote + tradeConfirmationPrompt(pair, proposal)
                    }
                }
            }
        }

        JarvisCommand.ConfirmForexTradeExecution -> {
            val proposal = pending
            if (proposal == null) {
                "There's no pending trade to confirm."
            } else {
                pending = null
                val entry = paperTradingEngine.confirmManualTrade(proposal.signal, proposal.verdict)
                // spec §25: never claim success without broker confirmation.
                when (entry.executionStatus) {
                    ExecutionStatus.FILLED -> "Trade executed. Filled ${proposal.verdict.orderRequest.pair.label} at ${entry.filledPrice}."
                    ExecutionStatus.REJECTED -> "The broker rejected the order: ${entry.reason}"
                    ExecutionStatus.PENDING -> "Order submitted and awaiting broker confirmation."
                    ExecutionStatus.UNKNOWN -> "Order status is unknown: ${entry.reason}. Not assuming it filled."
                    else -> entry.reason
                }
            }
        }

        JarvisCommand.CancelPendingForexTrade -> {
            pending = null
            "Trade cancelled."
        }

        JarvisCommand.EnableDemoForexTrading -> {
            settingsStore.update { it.copy(tradingMode = TradingMode.DEMO) }
            "Demo forex trading enabled."
        }

        // Confirmation for this command already happened in the generic yes/no flow before
        // execute() was even called (requiresConfirmation() == true for EnableLiveForexTrading).
        // No live broker adapter exists in this codebase yet (only DemoBrokerAdapter) — saying
        // "enabled" here would claim a capability that isn't actually wired to anything real,
        // which this project's own CapabilityRegistry convention explicitly avoids doing.
        JarvisCommand.EnableLiveForexTrading ->
            "Live trading isn't available yet — no live broker connection has been configured. " +
                "I can keep using demo trading, or you can connect a live broker in Trading Settings first."

        JarvisCommand.DisableLiveForexTrading -> {
            settingsStore.update { it.copy(tradingMode = TradingMode.DEMO) }
            "Live forex trading disabled. Demo mode active."
        }

        JarvisCommand.PauseForexTrading -> {
            emergencyStop.activateEmergencyStop("Trading paused by user.")
            "Forex trading paused."
        }

        JarvisCommand.ResumeForexTrading -> {
            emergencyStop.userClearEmergencyStop()
            "Forex trading resumed."
        }

        JarvisCommand.ForexEmergencyStop -> {
            pending = null // spec §20: cancel any pending AI execution request immediately
            emergencyStop.activateEmergencyStop("EMERGENCY STOP activated by user.")
            pushHud(null, "EMERGENCY STOP")
            "Emergency stop activated. All forex trading halted immediately."
        }

        else -> null
    }

    private suspend fun scanWatchlist(): String {
        val settings = settingsStore.current()
        pushHud(null, "SCANNING WATCHLIST")
        val lines = settings.watchlist.map { pair ->
            when (val proposal = paperTradingEngine.requestManualTrade(pair, settings)) {
                is PaperTradingEngine.ManualTradeProposal.ReadyToConfirm ->
                    "${pair.label}: ${proposal.signal.decision} (${proposal.signal.confidenceScore}/100)"
                is PaperTradingEngine.ManualTradeProposal.NotActionable ->
                    "${pair.label}: ${proposal.signal.decision}"
                is PaperTradingEngine.ManualTradeProposal.Blocked ->
                    "${pair.label}: blocked — ${proposal.reason}"
            }
        }
        return lines.joinToString("\n")
    }

    private fun describeProposal(pair: CurrencyPair, proposal: PaperTradingEngine.ManualTradeProposal): String = when (proposal) {
        is PaperTradingEngine.ManualTradeProposal.NotActionable -> "${pair.label}: ${proposal.signal.decision}. ${proposal.signal.reason}"
        is PaperTradingEngine.ManualTradeProposal.Blocked -> "${pair.label}: setup found but blocked — ${proposal.reason}"
        is PaperTradingEngine.ManualTradeProposal.ReadyToConfirm ->
            "${pair.label}: ${proposal.signal.decision} at ${proposal.signal.confidenceScore}/100 confluence. ${proposal.signal.reason} " +
                "Say \"trade ${pair.label}\" if you'd like to act on this."
    }

    /** Spec §17's exact structure: setup, direction, entry/stop/target, risk, R:R, then a
     * question — never a statement that the trade will happen. */
    private fun tradeConfirmationPrompt(pair: CurrencyPair, proposal: PaperTradingEngine.ManualTradeProposal.ReadyToConfirm): String {
        val order = proposal.verdict.orderRequest
        val rr = proposal.signal.riskRewardRatio?.let { "1:%.2f".format(it) } ?: "unavailable"
        return buildString {
            appendLine("${pair.label} ${order.direction} setup detected.")
            appendLine("Entry: ${proposal.signal.entry}")
            appendLine("Stop: ${order.stopLoss}")
            appendLine("Target: ${order.takeProfit}")
            appendLine("Risk: ${"%.2f".format(proposal.verdict.riskPercentOfEquity)}%")
            appendLine("R:R: $rr")
            appendLine("All risk checks passed.")
            append("Execute this trade?")
        }
    }

    private fun parsePair(symbol: String): CurrencyPair? =
        CurrencyPair.entries.firstOrNull { it.name.equals(symbol, ignoreCase = true) }

    private fun pushHud(pair: CurrencyPair?, status: String) {
        // Best-effort only: WallpaperEventBus requires Application.onCreate() initialization
        // with a real Android Context, which won't exist in unit tests and might not have run
        // yet in production. A cosmetic HUD update must never be able to break a trading
        // command's actual response, so any failure here is swallowed, not propagated.
        runCatching {
            WallpaperEventBus.emit(
                state = JarvisHudState.EXECUTING,
                focus = pair?.label ?: "FOREX",
                status = status
            )
        }
    }
}
