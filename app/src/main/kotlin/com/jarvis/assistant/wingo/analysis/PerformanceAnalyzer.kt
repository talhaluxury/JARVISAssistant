package com.jarvis.assistant.wingo.analysis

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.domain.WinGoConfig
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One resolved call: what was leaned towards, how confident, whether it was surfaced as a signal, and what happened. */
data class CallOutcome(
    val period: String,
    val side: BigSmall,
    val confidence: Double,
    val level: ConfidenceLevel,
    val signal: Signal,
    val agree: Int,
    val totalModels: Int,
    val actual: BigSmall,
    /** How many earlier occurrences backed the primary exact pattern (0 = none), its length and kind. */
    val patternSamples: Int = 0,
    val patternLength: Int = 0,
    val patternKind: String? = null
) {
    val correct: Boolean get() = side == actual
    val wasSignal: Boolean get() = signal != Signal.WAIT
}

data class PerfStats(
    val calls: Int,
    val correct: Int,
    val avgConfidence: Double?,
    val maxWinStreak: Int,
    val maxLossStreak: Int,
    val currentStreak: Int,
    val zScore: Double?
) {
    val wrong: Int get() = calls - correct
    val accuracy: Double? get() = if (calls == 0) null else correct.toDouble() / calls

    companion object {
        val EMPTY = PerfStats(0, 0, null, 0, 0, 0, null)
    }
}

data class BandStats(val label: String, val level: ConfidenceLevel, val calls: Int, val correct: Int) {
    val accuracy: Double? get() = if (calls == 0) null else correct.toDouble() / calls
}

/** Accuracy for an arbitrary grouping (e.g. by pattern sample size). */
data class BucketStats(val label: String, val calls: Int, val correct: Int) {
    val accuracy: Double? get() = if (calls == 0) null else correct.toDouble() / calls
}

/** Accuracy of every leaning call, and of the subset that was actually surfaced as a signal. */
data class AccuracyWindow(val allCalls: PerfStats, val signalled: PerfStats)

object PerformanceAnalyzer {

    fun summarize(calls: List<CallOutcome>): PerfStats {
        if (calls.isEmpty()) return PerfStats.EMPTY
        var correct = 0
        var confidenceSum = 0.0
        var winRun = 0
        var lossRun = 0
        var maxWin = 0
        var maxLoss = 0
        for (c in calls) {
            confidenceSum += c.confidence
            if (c.correct) {
                correct++
                winRun++
                lossRun = 0
                if (winRun > maxWin) maxWin = winRun
            } else {
                lossRun++
                winRun = 0
                if (lossRun > maxLoss) maxLoss = lossRun
            }
        }
        val n = calls.size
        val z = (correct.toDouble() / n - 0.5) / sqrt(0.25 / n)
        val current = if (winRun > 0) winRun else -lossRun
        return PerfStats(n, correct, confidenceSum / n, maxWin, maxLoss, current, z)
    }

    fun window(calls: List<CallOutcome>): AccuracyWindow =
        AccuracyWindow(summarize(calls), summarize(calls.filter { it.wasSignal }))

    /** Accuracy by confidence band. Bands come from the (configurable) thresholds. */
    fun byBand(calls: List<CallOutcome>, config: WinGoConfig): List<BandStats> {
        val low = (config.lowThreshold * 100).roundToInt()
        val medium = (config.mediumThreshold * 100).roundToInt()
        val high = (config.highThreshold * 100).roundToInt()
        val labels = mapOf(
            ConfidenceLevel.VERY_LOW to "<$low%",
            ConfidenceLevel.LOW to "$low-${medium - 1}%",
            ConfidenceLevel.MEDIUM to "$medium-${high - 1}%",
            ConfidenceLevel.HIGH to "$high%+"
        )
        return ConfidenceLevel.values().map { level ->
            val inBand = calls.filter { config.levelFor(it.confidence) == level }
            BandStats(labels.getValue(level), level, inBand.size, inBand.count { it.correct })
        }
    }

    /** Accuracy by how many earlier occurrences backed the primary pattern. */
    fun byPatternSamples(calls: List<CallOutcome>): List<BucketStats> {
        val buckets = listOf(
            "no pattern" to { c: CallOutcome -> c.patternSamples == 0 },
            "20-49 matches" to { c: CallOutcome -> c.patternSamples in 1..49 },
            "50-99 matches" to { c: CallOutcome -> c.patternSamples in 50..99 },
            "100-199 matches" to { c: CallOutcome -> c.patternSamples in 100..199 },
            "200+ matches" to { c: CallOutcome -> c.patternSamples >= 200 }
        )
        return buckets.map { (label, test) ->
            val group = calls.filter(test)
            BucketStats(label, group.size, group.count { it.correct })
        }
    }

    /** Running accuracy after each call - used for the accuracy chart. */
    fun cumulativeAccuracy(calls: List<CallOutcome>): List<Double> {
        val out = ArrayList<Double>(calls.size)
        var correct = 0
        calls.forEachIndexed { index, c ->
            if (c.correct) correct++
            out.add(correct.toDouble() / (index + 1))
        }
        return out
    }

    /** How often the ensemble was right when N of M models agreed. Returns agreeCount -> (calls, correct). */
    fun byAgreement(calls: List<CallOutcome>): Map<Int, Pair<Int, Int>> {
        val grouped = calls.groupBy { it.agree }
        return grouped.toSortedMap().mapValues { entry -> Pair(entry.value.size, entry.value.count { it.correct }) }
    }
}
