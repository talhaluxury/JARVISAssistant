package com.jarvis.assistant.wingo.voice

import com.jarvis.assistant.wingo.AnalyticsSnapshot
import com.jarvis.assistant.wingo.RoundPhase
import com.jarvis.assistant.wingo.WinGoUiState
import com.jarvis.assistant.wingo.analysis.ModelStatus
import com.jarvis.assistant.wingo.analysis.PatternBacktest
import com.jarvis.assistant.wingo.analysis.PatternEvidence
import com.jarvis.assistant.wingo.analysis.PerfStats
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal

/**
 * All user-facing wording lives here so chat, voice and overlay say the same honest thing.
 * It never promises an outcome; it reports what the models lean towards, on what evidence, and what was measured.
 */
object WinGoNarrator {

    fun phaseText(phase: RoundPhase): String = when (phase) {
        RoundPhase.IDLE -> "IDLE"
        RoundPhase.ANALYZING -> "ANALYZING"
        RoundPhase.PREDICTION_READY -> "PREDICTION READY"
        RoundPhase.WAITING_FOR_RESULT -> "WAITING FOR RESULT"
        RoundPhase.RESULT_DETECTED -> "RESULT DETECTED"
        RoundPhase.VERIFYING -> "VERIFYING"
        RoundPhase.PREDICTION_VERIFIED -> "PREDICTION VERIFIED"
    }

    fun signalText(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "No prediction yet."
        val side = p.side
        if (p.signal == Signal.WAIT || side == null) return p.waitReason ?: "WAIT — insufficient signal."
        return "NEXT ESTIMATE: ${side.name} — model probability ${Fmt.pct(p.confidence)} — signal ${p.signal.name} — " +
            "${p.agree}/${p.totalModels} models agree. A statistical estimate from past results, not a guarantee."
    }

    fun spokenSignal(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "There is no prediction yet."
        val side = p.side
        if (p.signal == Signal.WAIT || side == null) return "Wait. " + (p.waitReason ?: "Insufficient signal.")
        return "Next-round estimate is ${side.name} with ${Fmt.pct(p.confidence)} model probability. " +
            "${p.agree} of ${p.totalModels} models agree. Signal strength is ${p.signal.name.lowercase()}. This is not a guarantee."
    }

    private fun evidenceLine(e: PatternEvidence): String =
        "${e.context} (${e.kind.lowercase()}, ${e.length} rounds): seen ${e.occurrences} times, ${e.bigAfter} BIG / ${e.smallAfter} SMALL afterwards" +
            " → ${Fmt.pct(e.bigShare, 1)} BIG"

    /** What pattern was found in the recent rounds and what followed it historically. */
    fun pattern(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "No analysis yet."
        val sb = StringBuilder()
        if (p.recentSequence.isNotEmpty()) sb.append("Recent rounds (oldest → newest): ${p.recentSequence}\n")
        val e = p.pattern
        if (e != null) {
            sb.append("Pattern used: ").append(evidenceLine(e)).append('\n')
            val (lo, hi) = e.interval
            sb.append("Uncertainty (90% interval for BIG after this pattern): ${Fmt.pct(lo)} to ${Fmt.pct(hi)}.\n")
        } else {
            sb.append(p.patternNote ?: "No exact pattern has enough history yet.").append('\n')
        }
        p.similarPattern?.let { sb.append("Similar-pattern rule: ").append(evidenceLine(it)).append('\n') }
        sb.append("Patterns are only used with at least 20 earlier occurrences and are weighted by how well each length predicted out of sample.")
        return sb.toString()
    }

    /** The matching-pattern evidence of the Pattern and Similar Pattern models, one line per context length / rule. */
    fun matchingPatterns(state: WinGoUiState): String {
        val p = state.prediction ?: return state.message ?: "No analysis yet."
        val lines = ArrayList<String>()
        for (o in p.outputs) {
            if (o.modelName != "Pattern" && o.modelName != "Similar Pattern") continue
            if (o.parts.isEmpty()) {
                lines.add("${o.modelName}: ${o.reason}")
            } else {
                for (part in o.parts) lines.add("${o.modelName} ${part.name}: ${part.detail} (estimate ${Fmt.pct(part.probBig)} BIG)")
            }
        }
        return if (lines.isEmpty()) "No matching patterns to show yet." else lines.joinToString("\n")
    }

    /** Explains the current estimate from stored data. [requested] = the side the user asked about ("Why BIG?"). */
    fun why(state: WinGoUiState, requested: BigSmall? = null): String {
        val p = state.prediction ?: return state.message ?: "There is no prediction to explain yet."
        val side = p.side
        if (p.signal == Signal.WAIT || side == null) {
            val ask = if (requested != null) "There is no ${requested.name} estimate right now. " else ""
            return ask + (p.waitReason ?: "WAIT — insufficient signal.") + "\n" + modelLines(state)
        }
        val sb = StringBuilder()
        if (requested != null && requested != side) {
            val lean = p.outputs.count { it.prediction == requested }
            sb.append("The current estimate is ${side.name}, not ${requested.name}; $lean of ${p.totalModels} models lean ${requested.name}. ")
        }
        sb.append("${p.agree} of ${p.totalModels} models currently lean ${side.name}. ")
        val e = p.pattern
        if (e != null) {
            val followed = if (side == BigSmall.BIG) e.bigAfter else e.smallAfter
            sb.append("The current ${e.context} context occurred ${e.occurrences} times historically; ${side.name} followed it $followed times. ")
            val part = state.backtest?.partStatuses?.firstOrNull { it.name == "Pattern/len${e.length}" }
            val acc = part?.allTimeAccuracy
            if (part != null && acc != null && part.allTimeSamples >= 30) {
                sb.append("Out of sample, this pattern length has been right ${Fmt.pct(acc, 1)} of the time over ${part.allTimeSamples} calls. ")
            } else {
                sb.append("There is not yet enough out-of-sample history to measure how reliable this pattern length is. ")
            }
        } else if (p.patternNote != null) {
            sb.append(p.patternNote).append(". ")
        }
        sb.append("Model probability is ${Fmt.pct(p.confidence)}, so the signal is ${p.signal.name}")
        sb.append(p.measuredNote?.let { " ($it)" } ?: "")
        sb.append(". This is an estimate, not a guarantee.")
        return sb.toString()
    }

    fun modelLines(state: WinGoUiState): String {
        val p = state.prediction ?: return ""
        return p.outputs.joinToString("\n") { o ->
            if (o.abstained) "• ${o.modelName}: abstains (${o.reason})"
            else "• ${o.modelName}: ${o.prediction?.name ?: "no lean"} ${Fmt.pct(o.probBig)} BIG, ${o.sampleSize} samples — ${o.evidence.ifBlank { o.reason }}"
        }
    }

    private fun statusLine(m: ModelStatus): String {
        val all = m.allTimeAccuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
        val recent = m.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
        val off = if (m.disabled) " [switched off]" else ""
        return "• ${m.name}: all-time $all over ${m.allTimeSamples} calls, recent $recent, weight ${Fmt.num(m.weight, 2)}$off"
    }

    fun modelPerformance(state: WinGoUiState): String {
        val bt = state.backtest ?: return "No walk-forward record yet — more verified history is needed."
        val sb = StringBuilder("Walk-forward accuracy by model (each call used only earlier rounds):\n")
        sb.append(bt.modelStatuses.joinToString("\n") { statusLine(it) })
        val lengths = bt.partStatuses.filter { it.name.startsWith("Pattern/") }
        if (lengths.isNotEmpty()) sb.append("\nBy pattern length:\n").append(lengths.joinToString("\n") { statusLine(it) })
        return sb.toString()
    }

    private fun statsText(s: PerfStats): String {
        val acc = s.accuracy ?: return "no resolved calls yet"
        return "${Fmt.pct(acc, 1)} (${s.correct}/${s.calls})"
    }

    fun accuracy(state: WinGoUiState, snapshot: AnalyticsSnapshot?): String {
        val parts = ArrayList<String>()
        if (snapshot != null) {
            parts.add("Live, last 100 leaning calls: " + statsText(snapshot.last100.allCalls))
            parts.add("Live, signalled only: " + statsText(snapshot.last100.signalled))
        }
        val bt = state.backtest
        if (bt != null) {
            parts.add("Walk-forward last 100: ${statsText(bt.last100)}; last 500: ${statsText(bt.last500)}; all-time: ${statsText(bt.allCalls)}")
            parts.add(bt.shortText())
        } else {
            parts.add("No backtest yet — more verified history is needed.")
        }
        return parts.joinToString("\n")
    }

    fun verification(state: WinGoUiState): String =
        state.lastVerification?.toText() ?: "No prediction has been verified yet."

    fun patternBacktest(result: PatternBacktest?): String {
        if (result == null) return "There is no current pattern to backtest yet."
        val acc = result.accuracy
        return if (acc == null) {
            "Pattern ${result.context}: ${result.occurrences} occurrences, too few to backtest (needs 10 earlier occurrences before the first test)."
        } else {
            "Pattern ${result.context}: ${result.occurrences} occurrences. Walk-forward, using only earlier occurrences, the majority rule was right " +
                "${result.correct} of ${result.predictions} times (${Fmt.pct(acc, 1)})."
        }
    }

    /** What is stored, what is missing, and which pages to open to fill the holes. */
    fun coverage(state: WinGoUiState): String {
        if (state.historyCount == 0) return "No verified rounds stored yet. Open the game's history and JARVIS will save what it reads."
        val sb = StringBuilder()
        sb.append("Stored: ${state.historyCount} verified rounds.")
        val page = state.pageCurrent
        val total = state.pageTotal
        if (page != null && total != null) sb.append(" Now on history page $page/$total.")
        if (state.backfilledSession > 0) sb.append(" Older rounds saved this session: ${state.backfilledSession}.")
        sb.append("\n")
        if (state.missingRounds == 0) {
            sb.append("No missing rounds between stored periods.")
        } else {
            sb.append("Missing: ${state.missingRounds} rounds.\n")
            for (g in state.gaps) {
                val range = if (g.count == 1) "…${g.firstMissing.takeLast(5)}" else "…${g.firstMissing.takeLast(5)} to …${g.lastMissing.takeLast(5)}"
                val hint = g.pageHint?.let { " (around page $it)" } ?: ""
                sb.append("• $range — ${g.count} round(s)$hint\n")
            }
            sb.append("Open Game history, go back page by page and look for those periods; JARVIS saves every page it reads.")
        }
        return sb.toString().trimEnd()
    }

    fun history(results: List<RoundResult>): String {
        if (results.isEmpty()) return "No verified rounds stored yet."
        return "Last ${results.size} verified rounds (newest first):\n" +
            results.joinToString("\n") { "…${it.period.takeLast(4)}: ${it.number} ${it.bigSmall.name}" }
    }

    const val HELP = "Try: next estimate, what pattern, why big, matching patterns, last 20 results, accuracy, model performance, backtest this pattern."
}
