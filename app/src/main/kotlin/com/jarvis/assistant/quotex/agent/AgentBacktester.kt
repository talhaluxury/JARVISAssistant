package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import kotlin.math.sqrt

data class BacktestSignal(
    val index: Int,
    val direction: QuotexDecision,
    val strategies: List<String>,
    val regime: MarketRegime,
    val session: MarketSession,
    val bucket: String,
    /** null = draw (price ended exactly where it started). */
    val win: Boolean?
)

data class GroupStats(val samples: Int, val wins: Int) {
    val accuracy: Double? get() = if (samples == 0) null else wins.toDouble() / samples
}

data class SegmentReport(
    val name: String,
    val setups: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val hitRate: Double?,
    val zVsBreakEven: Double?,
    val maxWinStreak: Int,
    val maxLossStreak: Int,
    val byRegime: Map<String, GroupStats>,
    val bySession: Map<String, GroupStats>,
    val byStrategy: Map<String, GroupStats>,
    val byConfidence: Map<String, GroupStats>
) {
    val resolved: Int get() = wins + losses
}

data class AgentBacktestReport(
    val candlesTested: Int,
    val evaluations: Int,
    val waitCount: Int,
    val noTradeCount: Int,
    val dataUncertainCount: Int,
    val watchCount: Int,
    val breakEven: Double,
    val train: SegmentReport,
    val validation: SegmentReport,
    val outOfSample: SegmentReport,
    val signals: List<BacktestSignal>,
    /** True only if the untouched out-of-sample segment, by itself, shows a hit rate clearly above break-even. */
    val edgeConfirmedOutOfSample: Boolean,
    val calibrationNote: String,
    val summary: String
)

/**
 * Sections 18-21: chronological walk-forward test of the full analyzer. At every step only candles up to and
 * including that step are passed in; the outcome is looked up afterwards. The signal history is cut into
 * TRAIN / VALIDATION / OUT-OF-SAMPLE by time. Nothing in here tunes any parameter - the segments exist so
 * the user can tune on the first two and look at the last one only once.
 */
class AgentBacktester(
    private val config: AgentConfig = AgentConfig(),
    private val trainFraction: Double = 0.6,
    private val validationFraction: Double = 0.2,
    private val minOutOfSampleSamples: Int = 30,
    private val zThreshold: Double = 2.33
) {
    init {
        require(trainFraction > 0.0 && validationFraction > 0.0 && trainFraction + validationFraction < 1.0) {
            "train + validation fractions must leave room for an out-of-sample segment"
        }
    }

    fun run(candles: List<Candle>, step: Int = config.expiryCandles): AgentBacktestReport {
        // Edge verification is switched off here on purpose: the backtest is what MEASURES the edge.
        val analyzer = AgentAnalyzer(config.copy(requireVerifiedEdge = false))
        val horizon = config.expiryCandles
        val first = config.minCandles - 1
        val last = candles.size - 1 - horizon
        val signals = ArrayList<BacktestSignal>()
        var evaluations = 0
        var waits = 0
        var noTrades = 0
        var uncertain = 0
        var watches = 0

        var idx = first
        while (idx <= last) {
            val report = analyzer.analyze(candles.subList(0, idx + 1))
            evaluations++
            when (report.status) {
                AgentStatus.SETUP_DETECTED -> {
                    val start = candles[idx].close
                    val end = candles[idx + horizon].close
                    val win: Boolean? = when {
                        end == start -> null
                        report.direction == QuotexDecision.CALL -> end > start
                        else -> end < start
                    }
                    signals.add(BacktestSignal(idx, report.direction, report.strategies, report.regime, report.session, bucketOf(report), win))
                }
                AgentStatus.WAIT -> waits++
                AgentStatus.NO_TRADE -> noTrades++
                AgentStatus.DATA_UNCERTAIN -> uncertain++
                AgentStatus.WATCH -> watches++
            }
            idx += maxOf(1, step)
        }

        val range = if (last >= first) last - first + 1 else 0
        val trainEnd = first + (range * trainFraction).toInt()
        val validEnd = first + (range * (trainFraction + validationFraction)).toInt()
        val train = segment("TRAIN", signals.filter { it.index < trainEnd })
        val valid = segment("VALIDATION", signals.filter { it.index in trainEnd until validEnd })
        val oos = segment("OUT-OF-SAMPLE", signals.filter { it.index >= validEnd })

        val confirmed = oos.resolved >= minOutOfSampleSamples && (oos.zVsBreakEven ?: 0.0) >= zThreshold
        val calibration = calibrationNote(signals)
        val summary = buildString {
            append("Walk-forward over ${candles.size} candles: $evaluations evaluations, ${signals.size} setups, ")
            append("$waits wait, $watches watch, $noTrades no-trade, $uncertain data-uncertain. ")
            append("Break-even ${(config.breakEven * 1000).toInt() / 10.0}%. ")
            append("Out-of-sample: ${oos.resolved} resolved setups")
            oos.hitRate?.let { append(", hit rate ${(it * 1000).toInt() / 10.0}%") }
            append(if (confirmed) ". Edge confirmed out-of-sample (still not a guarantee)." else ". No edge confirmed out-of-sample.")
        }
        return AgentBacktestReport(
            candlesTested = candles.size, evaluations = evaluations, waitCount = waits, noTradeCount = noTrades,
            dataUncertainCount = uncertain, watchCount = watches, breakEven = config.breakEven, train = train,
            validation = valid, outOfSample = oos, signals = signals, edgeConfirmedOutOfSample = confirmed,
            calibrationNote = calibration, summary = summary
        )
    }

    private fun bucketOf(r: AgentReport): String {
        val f = if (r.conditionsTotal == 0) 0.0 else r.conditionsMet.toDouble() / r.conditionsTotal
        return when {
            f >= 0.85 -> "85%+ conditions"
            f >= 0.7 -> "70-85% conditions"
            else -> "60-70% conditions"
        }
    }

    private fun segment(name: String, list: List<BacktestSignal>): SegmentReport {
        val wins = list.count { it.win == true }
        val losses = list.count { it.win == false }
        val draws = list.count { it.win == null }
        val resolved = wins + losses
        val hit = if (resolved == 0) null else wins.toDouble() / resolved
        val z = if (resolved == 0) null else {
            val sd = sqrt(config.breakEven * (1.0 - config.breakEven) / resolved)
            if (sd > 0.0) ((hit ?: 0.0) - config.breakEven) / sd else null
        }
        var curW = 0
        var curL = 0
        var maxW = 0
        var maxL = 0
        for (s in list) {
            when (s.win) {
                true -> { curW++; curL = 0; if (curW > maxW) maxW = curW }
                false -> { curL++; curW = 0; if (curL > maxL) maxL = curL }
                null -> Unit
            }
        }
        return SegmentReport(
            name = name, setups = list.size, wins = wins, losses = losses, draws = draws, hitRate = hit,
            zVsBreakEven = z, maxWinStreak = maxW, maxLossStreak = maxL,
            byRegime = group(list) { listOf(it.regime.name) },
            bySession = group(list) { listOf(it.session.name) },
            byStrategy = group(list) { it.strategies },
            byConfidence = group(list) { listOf(it.bucket) }
        )
    }

    private fun group(list: List<BacktestSignal>, keys: (BacktestSignal) -> List<String>): Map<String, GroupStats> {
        val samples = HashMap<String, Int>()
        val wins = HashMap<String, Int>()
        for (s in list) {
            val w = s.win ?: continue
            for (k in keys(s)) {
                samples[k] = (samples[k] ?: 0) + 1
                if (w) wins[k] = (wins[k] ?: 0) + 1
            }
        }
        return samples.keys.sorted().associateWith { GroupStats(samples[it] ?: 0, wins[it] ?: 0) }
    }

    /** Section 21: does the "more conditions met" bucket actually do better than the lower ones? */
    private fun calibrationNote(signals: List<BacktestSignal>): String {
        val order = listOf("60-70% conditions", "70-85% conditions", "85%+ conditions")
        val stats = group(signals) { listOf(it.bucket) }
        val usable = order.filter { (stats[it]?.samples ?: 0) >= 20 }
        if (usable.size < 2) return "Not enough setups per confidence bucket (need 20+) to judge calibration."
        var monotonic = true
        for (j in 1 until usable.size) {
            val a = stats[usable[j - 1]]?.accuracy ?: 0.0
            val b = stats[usable[j]]?.accuracy ?: 0.0
            if (b + 1e-9 < a) monotonic = false
        }
        return if (monotonic) "Higher-confidence buckets did not perform worse than lower ones in this sample."
        else "CALIBRATION WARNING: a higher-confidence bucket performed worse than a lower one - confidence labels should not be trusted."
    }

    /** Hit rate of consecutive windows of [window] resolved setups, advancing by [stride] (rolling-window evaluation). */
    fun rolling(signals: List<BacktestSignal>, window: Int, stride: Int): List<Double> {
        val resolved = signals.filter { it.win != null }
        if (window <= 0 || stride <= 0 || resolved.size < window) return emptyList()
        val out = ArrayList<Double>()
        var s = 0
        while (s + window <= resolved.size) {
            val slice = resolved.subList(s, s + window)
            out.add(slice.count { it.win == true }.toDouble() / window)
            s += stride
        }
        return out
    }
}
