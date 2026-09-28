package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.quotex.QuotexAnalytics
import com.jarvis.assistant.quotex.QuotexUiState
import com.jarvis.assistant.quotex.analysis.QuotexPrediction
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.analysis.PerfStats
import com.jarvis.assistant.wingo.domain.Fmt

/** All user-facing Quotex wording. It states a lean and what was measured - never a promise. */
object QuotexNarrator {

    private fun word(d: QuotexDecision) = when (d) {
        QuotexDecision.CALL -> "CALL (higher)"
        QuotexDecision.PUT -> "PUT (lower)"
        else -> "WAIT"
    }

    private fun isSignal(p: QuotexPrediction) = p.isSignal && p.decision != QuotexDecision.WAIT

    fun signalText(state: QuotexUiState): String {
        val p = state.prediction ?: return state.message ?: "No analysis yet."
        if (!isSignal(p)) return p.waitReason ?: "WAIT — insufficient signal."
        val asset = state.asset?.let { "$it: " } ?: ""
        return "$asset${word(p.decision)} — confidence ${Fmt.pct(p.confidence)} — signal ${p.signal.name} — " +
            "${p.agree}/${p.totalModels} models agree — expiry ${state.expirySeconds}s. " +
            "A statistical lean from past prices, not a guarantee. You need more than ${Fmt.pct(state.breakEven, 1)} wins to break even."
    }

    fun spokenSignal(state: QuotexUiState): String {
        val p = state.prediction ?: return state.message ?: "There is no Quotex analysis yet."
        if (!isSignal(p)) return "Wait. " + (p.waitReason ?: "Insufficient signal.")
        return "Quotex lean is ${if (p.decision == QuotexDecision.CALL) "call, higher" else "put, lower"} with " +
            "${Fmt.pct(p.confidence)} confidence, expiry ${state.expirySeconds} seconds. This is not a guarantee."
    }

    fun why(state: QuotexUiState): String {
        val p = state.prediction ?: return state.message ?: "There is nothing to explain yet."
        val lines = p.outputs.joinToString("\n") { o ->
            if (o.abstained) "• ${o.modelName}: abstains (${o.reason})"
            else "• ${o.modelName}: ${if (o.probBig > 0.5) "higher" else "lower"} ${Fmt.pct(o.confidence)} — ${o.reason}"
        }
        val head = if (isSignal(p)) {
            "${p.agree} of ${p.totalModels} models favor ${word(p.decision)} at ${Fmt.pct(p.confidence)} confidence."
        } else {
            p.waitReason ?: "WAIT — insufficient signal."
        }
        return if (lines.isBlank()) head else head + "\n" + lines
    }

    private fun stats(s: PerfStats): String {
        val acc = s.accuracy ?: return "no resolved calls yet"
        return "${Fmt.pct(acc, 1)} (${s.correct}/${s.calls})"
    }

    fun accuracy(state: QuotexUiState, analytics: QuotexAnalytics?): String {
        val parts = ArrayList<String>()
        if (analytics != null) {
            parts.add("This session, all leaning calls: " + stats(analytics.session.allCalls))
            parts.add("This session, signalled only: " + stats(analytics.session.signalled))
        }
        val bt = state.backtest
        parts.add(bt?.shortText() ?: "No backtest yet — more price history is needed.")
        return parts.joinToString("\n")
    }

    fun price(state: QuotexUiState): String {
        val price = state.lastPrice ?: return "No price has been read yet."
        return "${state.asset ?: "Asset unknown"}: last read price $price (${state.candleCount} candles stored)."
    }

    fun confluence(state: QuotexUiState): String {
        val c = state.confluence ?: return "No strategy read yet - keep collecting price history."
        if (c.agreeingStrategies == 0) return "No setup: ${c.reason}"
        val lines = c.strategyResults.filter { it.totalCount > 0 }.joinToString("\n") { r ->
            "• ${r.strategyName}: ${if (r.direction.name == "WAIT") "no setup" else "${r.direction.name} (${r.satisfiedCount}/${r.totalCount} conditions)"}"
        }
        return "${c.quality.name.replace('_', ' ')} — signal state ${state.signalState.name}.\n${c.reason}\n$lines"
    }

    fun risk(state: QuotexUiState): String {
        val r = state.risk ?: return "No risk data yet."
        val status = if (r.paused) "TRADING PAUSED — ${r.reason}" else "Active."
        return "$status\nToday: ${r.winsToday}W / ${r.lossesToday}L (${r.tradesToday} tracked), P/L ${Fmt.num(r.dailyPnL, 2)}.\n" +
            "Losses in a row: ${r.consecutiveLosses} (pause at ${r.config.maxConsecutiveLosses}). " +
            "This is a discipline aid only - JARVIS never places trades, and Quotex's own limits are the real safeguard."
    }

    fun strategyPerformance(statuses: List<com.jarvis.assistant.quotex.analysis.StrategyStatus>): String {
        if (statuses.all { it.samples == 0 }) return "No strategy has a resolved call yet - keep collecting price history."
        return statuses.joinToString("\n") { s ->
            val acc = s.accuracy?.let { Fmt.pct(it, 1) } ?: "no resolved calls yet"
            "• ${s.name}: $acc over ${s.samples} calls, weight ${Fmt.num(s.weight, 2)}"
        }
    }

    const val HELP = "Try: signal, why, accuracy, price, confluence, strategy performance, risk, backtest, confluence backtest."
}
