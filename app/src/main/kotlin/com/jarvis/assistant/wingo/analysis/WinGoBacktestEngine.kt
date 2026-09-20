package com.jarvis.assistant.wingo.analysis

import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.PeriodFormat
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.WinGoConfig

data class BacktestReport(
    val totalRounds: Int,
    val evaluatedRounds: Int,
    val allCalls: PerfStats,
    val signalled: PerfStats,
    val skipped: Int,
    val bands: List<BandStats>,
    val modelStatuses: List<ModelStatus>,
    val edge: EdgeAssessment,
    val periodGaps: Int,
    val verdict: String
) {
    /** Human-readable report in the format the spec asks for. */
    fun toText(): String = buildString {
        appendLine("BACKTEST (walk-forward, no look-ahead)")
        appendLine("----------------")
        appendLine("Rounds in data: $totalRounds")
        appendLine("Rounds evaluated: $evaluatedRounds")
        if (periodGaps > 0) appendLine("Missing-period gaps in data: $periodGaps")
        appendLine()
        appendLine("ALL LEANING CALLS (raw ensemble lean, no gating)")
        appendStats(allCalls)
        appendLine()
        appendLine("SIGNALLED CALLS (after WAIT / edge gating)")
        appendStats(signalled)
        appendLine("Skipped (WAIT): $skipped")
        appendLine()
        appendLine("Accuracy by confidence (all leaning calls):")
        for (b in bands) {
            val acc = b.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
            appendLine("  ${b.label}: $acc (${b.calls} calls)")
        }
        appendLine()
        appendLine("Verdict: $verdict")
    }.trim()

    private fun StringBuilder.appendStats(stats: PerfStats) {
        appendLine("Predictions: ${stats.calls}")
        appendLine("Correct: ${stats.correct}")
        appendLine("Wrong: ${stats.wrong}")
        appendLine("Accuracy: ${stats.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"}")
        appendLine("Avg Confidence: ${stats.avgConfidence?.let { Fmt.pct(it, 1) } ?: "n/a"}")
        appendLine("Max winning streak: ${stats.maxWinStreak}")
        appendLine("Max losing streak: ${stats.maxLossStreak}")
    }

    /** One-line summary for the overlay / voice. */
    fun shortText(): String {
        val acc = allCalls.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
        return "Backtest: $acc over ${allCalls.calls} calls. $verdict"
    }
}

/** MODEL H - walks a chronological history, predicting every round from the past only. */
class WinGoBacktestEngine(private val config: WinGoConfig = WinGoConfig()) {

    /** Runs a backtest and also returns the engine that has just been "trained" walk-forward. */
    fun runWithEngine(
        results: List<RoundResult>,
        models: List<WinGoModel> = defaultWinGoModels()
    ): Pair<BacktestReport, WinGoAnalysisEngine> {
        val ordered = results.sortedBy { it.period }.distinctBy { it.period }
        val numbers = ordered.map { it.number }
        val engine = WinGoAnalysisEngine(config, models)
        val outcomes = ArrayList<CallOutcome>()
        var skipped = 0
        engine.walkForward(numbers, config.minHistoryForSignal) { index, prediction, actual ->
            val side = prediction.side
            if (side != null) {
                outcomes.add(
                    CallOutcome(
                        period = ordered[index].period, side = side, confidence = prediction.confidence,
                        level = prediction.level, signal = prediction.signal, agree = prediction.agree,
                        totalModels = prediction.totalModels, actual = actual
                    )
                )
            }
            if (!prediction.isSignal) skipped++
        }
        val evaluated = (numbers.size - config.minHistoryForSignal).coerceAtLeast(0)
        val all = PerformanceAnalyzer.summarize(outcomes)
        val signalled = PerformanceAnalyzer.summarize(outcomes.filter { it.wasSignal })
        val report = BacktestReport(
            totalRounds = numbers.size,
            evaluatedRounds = evaluated,
            allCalls = all,
            signalled = signalled,
            skipped = skipped,
            bands = PerformanceAnalyzer.byBand(outcomes, config),
            modelStatuses = engine.modelStatuses(),
            edge = engine.edgeAssessment(),
            periodGaps = countGaps(ordered),
            verdict = verdictFor(all, evaluated)
        )
        return Pair(report, engine)
    }

    fun run(results: List<RoundResult>, models: List<WinGoModel> = defaultWinGoModels()): BacktestReport =
        runWithEngine(results, models).first

    /** Consecutive same-day periods that are not exactly 1 apart mean rounds were missed. */
    fun countGaps(ordered: List<RoundResult>): Int {
        var gaps = 0
        for (i in 1 until ordered.size) {
            val a = ordered[i - 1].period
            val b = ordered[i].period
            if (PeriodFormat.dayOf(a) != PeriodFormat.dayOf(b)) continue
            val av = a.toLongOrNull() ?: continue
            val bv = b.toLongOrNull() ?: continue
            if (bv - av != 1L) gaps++
        }
        return gaps
    }

    private fun verdictFor(all: PerfStats, evaluated: Int): String {
        val z = all.zScore
        val acc = all.accuracy
        if (evaluated < 200 || z == null || acc == null) {
            return "Not enough data for a meaningful verdict (need 200+ evaluated rounds)."
        }
        return when {
            z >= config.edgeZThreshold ->
                "Measured accuracy ${Fmt.pct(acc, 1)} is above a coin flip (z=${Fmt.num(z)}). Confirm on fresh data before trusting it."
            z <= -config.edgeZThreshold ->
                "Measured accuracy ${Fmt.pct(acc, 1)} is WORSE than a coin flip (z=${Fmt.num(z)})."
            else ->
                "No statistically significant edge over a coin flip (accuracy ${Fmt.pct(acc, 1)}, z=${Fmt.num(z)}). Treat any lean as noise."
        }
    }
}

/** Helper for the analytics/chat screens: numbers-only convenience. */
fun List<RoundResult>.bigSmallLetters(): String = joinToString(" ") { it.bigSmall.letter.toString() }
