package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.domain.QuotexConfig
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.analysis.BandStats
import com.jarvis.assistant.wingo.analysis.CallOutcome
import com.jarvis.assistant.wingo.analysis.EdgeAssessment
import com.jarvis.assistant.wingo.analysis.ModelStatus
import com.jarvis.assistant.wingo.analysis.PerfStats
import com.jarvis.assistant.wingo.analysis.PerformanceAnalyzer
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.Fmt

data class QuotexBacktestReport(
    val candles: Int,
    val evaluated: Int,
    val expiryCandles: Int,
    val expirySeconds: Int,
    /** Every resolved lean (overlapping expiries, so correlated - shown for reference only). */
    val allCalls: PerfStats,
    val signalled: PerfStats,
    /** Non-overlapping leaning calls (every expiry-th candle): the fair basis for accuracy, z and P/L. */
    val independentCalls: PerfStats,
    val skipped: Int,
    val bands: List<BandStats>,
    val modelStatuses: List<ModelStatus>,
    val edge: EdgeAssessment,
    val breakEven: Double,
    val payout: Double,
    /** Simulated result of flat 1-unit stakes on the independent calls. */
    val simulatedProfitUnits: Double,
    val gaps: Int,
    val verdict: String
) {
    fun toText(): String = buildString {
        appendLine("QUOTEX BACKTEST (walk-forward, no look-ahead)")
        appendLine("----------------")
        appendLine("Candles in data: $candles  (evaluated: $evaluated)")
        appendLine("Expiry: $expiryCandles candles = ${expirySeconds}s")
        if (gaps > 0) appendLine("Missing-candle gaps in data: $gaps")
        appendLine()
        appendLine("INDEPENDENT CALLS (non-overlapping - the fair test)")
        appendStats(independentCalls)
        appendLine("Break-even accuracy at ${Fmt.pct(payout)} payout: ${Fmt.pct(breakEven, 1)}")
        appendLine("Simulated P/L, 1 unit per call: ${(if (simulatedProfitUnits >= 0) "+" else "") + Fmt.num(simulatedProfitUnits, 1)} units")
        appendLine()
        appendLine("SIGNALLED CALLS (after WAIT / edge gating, overlapping)")
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

    private fun StringBuilder.appendStats(s: PerfStats) {
        appendLine("Predictions: ${s.calls}")
        appendLine("Correct: ${s.correct}")
        appendLine("Wrong: ${s.wrong}")
        appendLine("Accuracy: ${s.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"}")
        appendLine("Avg Confidence: ${s.avgConfidence?.let { Fmt.pct(it, 1) } ?: "n/a"}")
        appendLine("Max winning streak: ${s.maxWinStreak}")
        appendLine("Max losing streak: ${s.maxLossStreak}")
    }

    fun shortText(): String {
        val acc = independentCalls.accuracy?.let { Fmt.pct(it, 1) } ?: "n/a"
        return "Backtest: $acc over ${independentCalls.calls} independent calls (break-even ${Fmt.pct(breakEven, 1)}). $verdict"
    }
}

class QuotexBacktestEngine(private val config: QuotexConfig = QuotexConfig()) {

    fun runWithEngine(
        candles: List<Candle>,
        models: List<QuotexModel> = defaultQuotexModels()
    ): Pair<QuotexBacktestReport, QuotexEngine> {
        val ordered = candles.sortedBy { it.openTimeMs }.distinctBy { it.openTimeMs }
        val engine = QuotexEngine(config, models)
        val outcomes = ArrayList<CallOutcome>()
        val independentIdx = HashSet<Int>()
        var skipped = 0
        engine.walkForward(ordered) { idx, prediction, higher ->
            if (prediction.candleCount >= config.minCandlesForSignal) {
                if (!prediction.isSignal) skipped++
                if (prediction.lean != QuotexDecision.WAIT && higher != null) {
                    outcomes.add(
                        CallOutcome(
                            period = idx.toString(),
                            side = if (prediction.lean == QuotexDecision.CALL) BigSmall.BIG else BigSmall.SMALL,
                            confidence = prediction.confidence, level = prediction.level, signal = prediction.signal,
                            agree = prediction.agree, totalModels = prediction.totalModels,
                            actual = if (higher) BigSmall.BIG else BigSmall.SMALL
                        )
                    )
                    if (idx % config.expiryCandles == 0) independentIdx.add(outcomes.size - 1)
                }
            }
        }
        val independent = outcomes.filterIndexed { i, _ -> i in independentIdx }
        val ind = PerformanceAnalyzer.summarize(independent)
        val wins = ind.correct
        val losses = ind.wrong
        val profit = wins * config.payout - losses
        val evaluated = (ordered.size - config.minCandlesForSignal).coerceAtLeast(0)
        val report = QuotexBacktestReport(
            candles = ordered.size, evaluated = evaluated,
            expiryCandles = config.expiryCandles, expirySeconds = config.expirySeconds,
            allCalls = PerformanceAnalyzer.summarize(outcomes),
            signalled = PerformanceAnalyzer.summarize(outcomes.filter { it.wasSignal }),
            independentCalls = ind, skipped = skipped,
            bands = PerformanceAnalyzer.byBand(outcomes, config.toWinGoConfig()),
            modelStatuses = engine.modelStatuses(), edge = engine.edgeAssessment(),
            breakEven = config.breakEvenAccuracy, payout = config.payout, simulatedProfitUnits = profit,
            gaps = CandleRuns.countGaps(ordered, config.candleMs), verdict = verdictFor(ind)
        )
        return Pair(report, engine)
    }

    fun run(candles: List<Candle>, models: List<QuotexModel> = defaultQuotexModels()): QuotexBacktestReport =
        runWithEngine(candles, models).first

    private fun verdictFor(ind: PerfStats): String {
        val z = ind.zScore
        val acc = ind.accuracy
        if (ind.calls < 100 || z == null || acc == null) {
            return "Not enough independent calls for a meaningful verdict (need 100+, have ${ind.calls})."
        }
        val be = config.breakEvenAccuracy
        return when {
            z >= config.edgeZThreshold && acc > be ->
                "Measured accuracy ${Fmt.pct(acc, 1)} is above break-even ${Fmt.pct(be, 1)} (z=${Fmt.num(z)}). Confirm on fresh data before trusting it."
            z >= config.edgeZThreshold ->
                "Above a coin flip (${Fmt.pct(acc, 1)}, z=${Fmt.num(z)}) but NOT above break-even ${Fmt.pct(be, 1)}: still loses money at this payout."
            z <= -config.edgeZThreshold ->
                "Measured accuracy ${Fmt.pct(acc, 1)} is WORSE than a coin flip (z=${Fmt.num(z)})."
            else ->
                "No statistically significant edge (accuracy ${Fmt.pct(acc, 1)} vs break-even ${Fmt.pct(be, 1)}, z=${Fmt.num(z)}). Treat any lean as noise."
        }
    }
}
