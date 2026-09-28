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
    val verdict: String,
    /** Walk-forward accuracy of all leaning calls over the most recent 100 / 500 calls. */
    val last100: PerfStats = PerfStats.EMPTY,
    val last500: PerfStats = PerfStats.EMPTY,
    /** Every sub-estimate (window, transition order, pattern length, similarity rule) with its own record. */
    val partStatuses: List<ModelStatus> = emptyList(),
    val bySampleSize: List<BucketStats> = emptyList(),
    /** Calls that had an exact pattern with enough support behind them. */
    val patternCalls: PerfStats = PerfStats.EMPTY
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
        appendLine("Measured walk-forward accuracy (all leaning calls):")
        appendLine("  Last 100: ${accText(last100)}")
        appendLine("  Last 500: ${accText(last500)}")
        appendLine("  All-time: ${accText(allCalls)}")
        appendLine()
        appendLine("Accuracy by confidence (all leaning calls):")
        for (b in bands) {
            val acc = b.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
            appendLine("  ${b.label}: $acc (${b.calls} calls)")
        }
        appendLine()
        appendLine("Accuracy by model (all-time, walk-forward):")
        for (m in modelStatuses) appendLine("  ${statusText(m)}")
        val lengths = partStatuses.filter { it.name.startsWith("Pattern/") }
        if (lengths.isNotEmpty()) {
            appendLine()
            appendLine("Accuracy by exact-pattern length:")
            for (m in lengths) appendLine("  ${statusText(m)}")
        }
        val similar = partStatuses.filter { it.name.startsWith("Similar Pattern/") }
        if (similar.isNotEmpty()) {
            appendLine()
            appendLine("Similar-pattern rules:")
            for (m in similar) appendLine("  ${statusText(m)}")
        }
        val others = partStatuses.filter { !it.name.startsWith("Pattern/") && !it.name.startsWith("Similar Pattern/") }
        if (others.isNotEmpty()) {
            appendLine()
            appendLine("Other sub-estimates (windows, transition orders, ...):")
            for (m in others) appendLine("  ${statusText(m)}")
        }
        if (bySampleSize.any { it.calls > 0 }) {
            appendLine()
            appendLine("Accuracy by pattern sample size:")
            for (b in bySampleSize) appendLine("  ${b.label}: ${b.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"} (${b.calls} calls)")
        }
        appendLine()
        appendLine("Calls backed by an exact pattern: ${accText(patternCalls)}")
        appendLine()
        appendLine("Verdict: $verdict")
    }.trim()

    private fun accText(s: PerfStats): String = s.accuracy?.let { "${Fmt.pct(it, 1)} (${s.correct}/${s.calls})" } ?: "n/a"

    private fun statusText(m: ModelStatus): String {
        val all = m.allTimeAccuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
        val off = if (m.disabled) "  [switched off]" else ""
        return "${m.name}: $all over ${m.allTimeSamples} calls, weight ${Fmt.num(m.weight, 2)}$off"
    }

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
                        totalModels = prediction.totalModels, actual = actual,
                        patternSamples = prediction.pattern?.occurrences ?: 0,
                        patternLength = prediction.pattern?.length ?: 0,
                        patternKind = prediction.pattern?.kind
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
            verdict = verdictFor(all, evaluated),
            last100 = PerformanceAnalyzer.summarize(outcomes.takeLast(100)),
            last500 = PerformanceAnalyzer.summarize(outcomes.takeLast(500)),
            partStatuses = engine.partStatuses(),
            bySampleSize = PerformanceAnalyzer.byPatternSamples(outcomes),
            patternCalls = PerformanceAnalyzer.summarize(outcomes.filter { it.patternSamples > 0 })
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
                "NO VERIFIED EDGE — no statistically significant edge over a coin flip (accuracy ${Fmt.pct(acc, 1)}, z=${Fmt.num(z)}). Treat any lean as noise."
        }
    }
}

/** Helper for the analytics/chat screens: numbers-only convenience. */
fun List<RoundResult>.bigSmallLetters(): String = joinToString(" ") { it.bigSmall.letter.toString() }
