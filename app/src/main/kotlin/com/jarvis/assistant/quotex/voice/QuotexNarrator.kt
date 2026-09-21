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

    const val HELP = "Try: signal, why, accuracy, price, backtest, backtest last 100."
}
