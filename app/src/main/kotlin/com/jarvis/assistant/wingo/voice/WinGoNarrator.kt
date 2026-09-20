package com.jarvis.assistant.wingo.voice

import com.jarvis.assistant.wingo.AnalyticsSnapshot
import com.jarvis.assistant.wingo.WinGoUiState
import com.jarvis.assistant.wingo.analysis.PerfStats
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal

/**
 * All user-facing wording lives here so chat, voice and overlay say the same honest thing.
 * It never promises an outcome; it reports what the models lean towards and what was measured.
 */
object WinGoNarrator {

    fun signalText(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "No prediction yet."
        val side = p.side
        if (p.signal == Signal.WAIT || side == null) return p.waitReason ?: "WAIT — insufficient signal."
        return "${side.name} — confidence ${Fmt.pct(p.confidence)} — signal ${p.signal.name} — " +
            "${p.agree}/${p.totalModels} models agree. A statistical lean from past results, not a guarantee."
    }

    fun spokenSignal(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "There is no prediction yet."
        val side = p.side
        if (p.signal == Signal.WAIT || side == null) return "Wait. " + (p.waitReason ?: "Insufficient signal.")
        return "Current lean is ${side.name} with ${Fmt.pct(p.confidence)} confidence. " +
            "Signal strength is ${p.signal.name.lowercase()}. This is not a guarantee."
    }

    fun why(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "There is no prediction to explain yet."
        val side = p.side
        if (p.signal == Signal.WAIT || side == null) {
            return (p.waitReason ?: "WAIT — insufficient signal.") + "\n" + modelLines(state)
        }
        val recent = p.outputs.firstOrNull { it.modelName == "Recent Frequency" }?.reason
        val band = state.backtest?.bands?.firstOrNull { it.level == p.level }
        val bandText = if (band == null || band.calls < 30 || band.accuracy == null) {
            "There is not enough history in this confidence range to measure how often it was right."
        } else {
            "Historically, calls in this confidence range were right ${Fmt.pct(band.accuracy!!, 1)} of the time (${band.calls} calls)."
        }
        return "${p.agree} of ${p.totalModels} models currently favor ${side.name}. " +
            (if (recent != null) "$recent. " else "") +
            "Current confidence is ${Fmt.pct(p.confidence)}. $bandText Signal strength is ${p.signal.name}."
    }

    fun modelLines(state: WinGoUiState): String {
        val p = state.prediction ?: return ""
        return p.outputs.joinToString("\n") { o ->
            if (o.abstained) "• ${o.modelName}: abstains (${o.reason})"
            else "• ${o.modelName}: ${o.prediction?.name ?: "no lean"} ${Fmt.pct(o.confidence)} — ${o.reason}"
        }
    }

    fun accuracy(state: WinGoUiState, snapshot: AnalyticsSnapshot?): String {
        val parts = ArrayList<String>()
        if (snapshot != null) {
            parts.add("Live, last 100 leaning calls: " + statsText(snapshot.last100.allCalls))
            parts.add("Live, signalled only: " + statsText(snapshot.last100.signalled))
        }
        val bt = state.backtest
        if (bt != null) parts.add(bt.shortText()) else parts.add("No backtest yet — more verified history is needed.")
        return parts.joinToString("\n")
    }

    private fun statsText(s: PerfStats): String {
        val acc = s.accuracy ?: return "no resolved calls yet"
        return "${Fmt.pct(acc, 1)} (${s.correct}/${s.calls})"
    }

    fun history(results: List<RoundResult>): String {
        if (results.isEmpty()) return "No verified rounds stored yet."
        return "Last ${results.size} verified rounds (newest first):\n" +
            results.joinToString("\n") { "…${it.period.takeLast(4)}: ${it.number} ${it.bigSmall.name}" }
    }

    const val HELP = "Try: signal, why, accuracy, history, last 20 rounds, backtest last 100."
}
