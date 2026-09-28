package com.jarvis.assistant.wingo.analysis

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.domain.WinGoConfig
import kotlin.math.sqrt

/** Sliding window of hit/miss outcomes with a running hit count. */
class RollingHits(private val window: Int) {
    private val buffer = ArrayDeque<Boolean>()

    var hits: Int = 0
        private set

    val samples: Int get() = buffer.size

    fun record(hit: Boolean) {
        buffer.addLast(hit)
        if (hit) hits++
        while (buffer.size > window) {
            if (buffer.removeFirst()) hits--
        }
    }

    val accuracy: Double? get() = if (buffer.isEmpty()) null else hits.toDouble() / buffer.size

    /** How many standard errors the hit rate is above a 50% coin flip. */
    fun zScore(): Double? {
        if (buffer.isEmpty()) return null
        val se = sqrt(0.25 / buffer.size)
        return (hits.toDouble() / buffer.size - 0.5) / se
    }
}

/**
 * Online walk-forward record of one model (or one part of a model). A model that is no better than a coin
 * flip gets a small weight, a measurably better one a larger weight, and one that is clearly WORSE than chance
 * over enough calls is switched off (weight 0) - it keeps being tracked, so it can come back if it starts to work.
 * Only PAST outcomes ever reach it, so it cannot leak future information into a backtest.
 */
class ModelTracker(window: Int = 200, private val minSamples: Int = 30) {
    private val rolling = RollingHits(window)

    var totalSamples: Int = 0
        private set
    var totalHits: Int = 0
        private set

    fun record(hit: Boolean) {
        rolling.record(hit)
        totalSamples++
        if (hit) totalHits++
    }

    val samples: Int get() = rolling.samples
    val accuracy: Double? get() = rolling.accuracy
    val allTimeAccuracy: Double? get() = if (totalSamples == 0) null else totalHits.toDouble() / totalSamples

    fun isDisabled(): Boolean {
        if (rolling.samples < DISABLE_MIN_SAMPLES) return false
        val z = rolling.zScore() ?: return false
        return z <= DISABLE_Z
    }

    fun weight(): Double {
        if (isDisabled()) return 0.0
        if (rolling.samples < minSamples) return 1.0
        val z = rolling.zScore() ?: return 1.0
        return (1.0 + 0.25 * z).coerceIn(0.1, 2.0)
    }

    companion object {
        const val DISABLE_MIN_SAMPLES = 100
        const val DISABLE_Z = -1.0
    }
}

data class ModelStatus(
    val name: String,
    val samples: Int,
    val accuracy: Double?,
    val weight: Double,
    val allTimeSamples: Int = 0,
    val allTimeAccuracy: Double? = null,
    val disabled: Boolean = false
)

data class EnsembleResult(
    val side: BigSmall?,
    val probBig: Double,
    val agree: Int,
    val voting: Int,
    val totalModels: Int,
    val weightedAgreement: Double
) {
    val confidence: Double
        get() = when (side) {
            null -> 0.5
            BigSmall.BIG -> probBig
            BigSmall.SMALL -> 1.0 - probBig
        }
}

/** Transparent ensemble: a weighted mean of each voting model's P(BIG). Models with weight 0 (switched off) do not vote. */
class EnsemblePredictor {
    fun combine(outputs: List<ModelOutput>, weights: Map<String, Double>): EnsembleResult {
        var weightSum = 0.0
        var probSum = 0.0
        var voting = 0
        for (o in outputs) {
            if (o.abstained) continue
            val w = weights[o.modelName] ?: 1.0
            if (w <= 0.0) continue
            voting++
            weightSum += w
            probSum += w * o.probBig
        }
        if (voting == 0 || weightSum <= 0.0) return EnsembleResult(null, 0.5, 0, 0, outputs.size, 0.0)
        val p = probSum / weightSum
        val side = when {
            p > 0.5 + 1e-9 -> BigSmall.BIG
            p < 0.5 - 1e-9 -> BigSmall.SMALL
            else -> null
        }
        if (side == null) return EnsembleResult(null, p, 0, voting, outputs.size, 0.0)
        var agree = 0
        var agreeWeight = 0.0
        for (o in outputs) {
            if (o.abstained) continue
            val w = weights[o.modelName] ?: 1.0
            if (w <= 0.0) continue
            if (o.prediction == side) {
                agree++
                agreeWeight += w
            }
        }
        return EnsembleResult(side, p, agree, voting, outputs.size, agreeWeight / weightSum)
    }
}

/** Does the record of would-be signals beat a coin flip by more than luck would explain? */
data class EdgeAssessment(
    val samples: Int,
    val hits: Int,
    val accuracy: Double?,
    val zScore: Double?,
    val verified: Boolean,
    val summary: String
)

data class WinGoPrediction(
    val forPeriod: String?,
    val side: BigSmall?,
    val probBig: Double,
    val confidence: Double,
    val level: ConfidenceLevel,
    val signal: Signal,
    val agree: Int,
    val voting: Int,
    val totalModels: Int,
    /** True when the raw call cleared the confidence and agreement thresholds (before the edge gate). */
    val isCandidate: Boolean,
    val waitReason: String?,
    val outputs: List<ModelOutput>,
    val weights: Map<String, Double>,
    val historySize: Int,
    val edge: EdgeAssessment,
    /** The exact sequence with the best (weight x evidence) support, e.g. BSSBBS seen 42 times. */
    val pattern: PatternEvidence? = null,
    /** The best similar-sequence rule. */
    val similarPattern: PatternEvidence? = null,
    /** Why no pattern was used (e.g. too few occurrences). */
    val patternNote: String? = null,
    /** The last 8 rounds, oldest first, as B/S letters. */
    val recentSequence: String = "",
    /** Set when the signal strength was capped by measured (not claimed) performance. */
    val measuredNote: String? = null
) {
    val isSignal: Boolean get() = signal != Signal.WAIT
}

class WinGoAnalysisEngine(
    val config: WinGoConfig = WinGoConfig(),
    private val models: List<WinGoModel> = defaultWinGoModels()
) {
    private val ensemble = EnsemblePredictor()
    private val trackers: Map<String, ModelTracker> = models.associate { it.name to ModelTracker() }
    private val partTrackers = HashMap<String, ModelTracker>()
    private val candidateRecord = RollingHits(config.edgeLookback)

    private fun partKey(model: String, part: String) = "$model/$part"

    fun edgeAssessment(): EdgeAssessment {
        val n = candidateRecord.samples
        val z = candidateRecord.zScore()
        val acc = candidateRecord.accuracy
        val verified = n >= config.edgeMinSamples && z != null && z >= config.edgeZThreshold
        val summary = if (n == 0 || acc == null || z == null) {
            "No measured record of candidate calls yet"
        } else {
            "Candidate calls hit ${candidateRecord.hits}/$n (${Fmt.pct(acc, 1)}, z=${Fmt.num(z)}); " +
                "need at least ${config.edgeMinSamples} calls and z >= ${Fmt.num(config.edgeZThreshold, 2)}"
        }
        return EdgeAssessment(n, candidateRecord.hits, acc, z, verified, summary)
    }

    fun modelStatuses(): List<ModelStatus> = models.map {
        val t = trackers[it.name]
        ModelStatus(
            it.name, t?.samples ?: 0, t?.accuracy, t?.weight() ?: 1.0,
            t?.totalSamples ?: 0, t?.allTimeAccuracy, t?.isDisabled() ?: false
        )
    }

    /** Walk-forward record of every sub-estimate: window sizes, transition orders, pattern lengths, similarity rules... */
    fun partStatuses(): List<ModelStatus> = partTrackers.entries.sortedBy { it.key }.map { (key, t) ->
        ModelStatus(key, t.samples, t.accuracy, t.weight(), t.totalSamples, t.allTimeAccuracy, t.isDisabled())
    }

    private fun subWeight(model: String, part: ModelPart): Double = partTrackers[partKey(model, part.name)]?.weight() ?: 1.0

    /** Re-weights a model's parts by their own walk-forward record. */
    private fun applyParts(output: ModelOutput): ModelOutput {
        if (output.abstained || output.parts.isEmpty()) return output
        val p = combineParts(output.parts) { subWeight(output.modelName, it) }
        if (p == null) return output.copy(abstained = true, reason = output.reason + " (all sub-rules currently switched off)")
        return output.copy(probBig = p)
    }

    private fun bestEvidence(output: ModelOutput?): PatternEvidence? {
        if (output == null || output.parts.isEmpty()) return null
        var best: ModelPart? = null
        var bestScore = -1.0
        for (part in output.parts) {
            if (part.evidence == null) continue
            val score = subWeight(output.modelName, part) * part.strength
            if (score > bestScore) {
                bestScore = score
                best = part
            }
        }
        return best?.evidence
    }

    private fun minLevel(a: ConfidenceLevel, b: ConfidenceLevel): ConfidenceLevel = if (a.ordinal <= b.ordinal) a else b

    /** Pure with respect to engine state: predicting never changes weights or the edge record. */
    fun predict(history: RoundHistory): WinGoPrediction {
        val edge = edgeAssessment()
        if (history.size < config.minHistoryForSignal) {
            return blank(history.size, edge, "Insufficient historical data (${history.size}/${config.minHistoryForSignal} rounds).")
        }
        val outputs = models.map { applyParts(it.predict(history)) }
        val weights = models.associate { it.name to (trackers[it.name]?.weight() ?: 1.0) }
        val combined = ensemble.combine(outputs, weights)
        val level = config.levelFor(combined.confidence)
        val side = combined.side

        val patternOutput = outputs.firstOrNull { it.modelName == "Pattern" }
        val similarOutput = outputs.firstOrNull { it.modelName == "Similar Pattern" }
        val pattern = bestEvidence(patternOutput)
        val similar = bestEvidence(similarOutput)
        val patternNote = if (pattern == null && patternOutput != null && patternOutput.abstained) patternOutput.reason else null

        var isCandidate = false
        var signal = Signal.WAIT
        var measuredNote: String? = null
        val reason: String? = when {
            side == null || level == ConfidenceLevel.VERY_LOW ->
                "WAIT — insufficient signal. No strong signal: ensemble probability ${Fmt.pct(combined.confidence)} is below the ${Fmt.pct(config.lowThreshold)} threshold."
            combined.weightedAgreement < config.minWeightedAgreement ->
                "Signal unclear — WAIT. Only ${combined.agree} of ${combined.voting} voting models agree."
            else -> {
                isCandidate = true
                if (config.requireVerifiedEdge && !edge.verified) {
                    "WAIT — NO VERIFIED EDGE: no statistically verified edge yet. ${edge.summary}."
                } else {
                    // Signal strength may never exceed what has actually been measured out of sample.
                    var strength = level
                    val measured = edge.accuracy
                    if (edge.samples >= MEASURED_CAP_MIN_SAMPLES && measured != null) {
                        val measuredLevel = config.levelFor(measured)
                        if (measuredLevel.ordinal < strength.ordinal) {
                            measuredNote = "Signal capped at ${measuredLevel.name}: measured accuracy of comparable calls is ${Fmt.pct(measured, 1)} over ${edge.samples} calls."
                            strength = measuredLevel
                        }
                    }
                    if (strength == ConfidenceLevel.VERY_LOW) {
                        "WAIT — measured accuracy of comparable calls is only ${Fmt.pct(measured ?: 0.5, 1)} over ${edge.samples} calls; no reliable signal."
                    } else {
                        signal = Signal.from(strength)
                        null
                    }
                }
            }
        }
        return WinGoPrediction(
            forPeriod = null, side = side, probBig = combined.probBig, confidence = combined.confidence,
            level = level, signal = signal, agree = combined.agree, voting = combined.voting,
            totalModels = combined.totalModels, isCandidate = isCandidate, waitReason = reason,
            outputs = outputs, weights = weights, historySize = history.size, edge = edge,
            pattern = pattern, similarPattern = similar, patternNote = patternNote,
            recentSequence = PatternMining.suffix(history.bits, 8), measuredNote = measuredNote
        )
    }

    /** Feed back the real result once it is known. Only ever called AFTER [predict] for that round. */
    fun observe(prediction: WinGoPrediction, actual: BigSmall) {
        for (o in prediction.outputs) {
            val side = o.prediction ?: continue
            trackers[o.modelName]?.record(side == actual)
        }
        // Sub-estimates are tracked even when the model as a whole abstained or a rule is currently switched off.
        for (o in prediction.outputs) {
            for (part in o.parts) {
                val lean = when {
                    part.probBig > 0.5 + 1e-9 -> BigSmall.BIG
                    part.probBig < 0.5 - 1e-9 -> BigSmall.SMALL
                    else -> continue
                }
                partTrackers.getOrPut(partKey(o.modelName, part.name)) { ModelTracker() }.record(lean == actual)
            }
        }
        val side = prediction.side
        if (prediction.isCandidate && side != null) candidateRecord.record(side == actual)
    }

    /**
     * Chronological walk-forward: predict round i from rounds 0 until i only, compare with the real
     * result, update the trackers, move on. Nothing is shuffled and nothing from round i or later is
     * visible when round i is predicted.
     */
    fun walkForward(
        numbers: List<Int>,
        startIndex: Int,
        onStep: ((index: Int, prediction: WinGoPrediction, actual: BigSmall) -> Unit)? = null
    ) {
        for (i in startIndex.coerceAtLeast(1) until numbers.size) {
            val history = RoundHistory.window(numbers, i, config.modelHistoryCap)
            val prediction = predict(history)
            val actual = BigSmall.fromNumber(numbers[i])
            onStep?.invoke(i, prediction, actual)
            observe(prediction, actual)
        }
    }

    private fun blank(historySize: Int, edge: EdgeAssessment, reason: String) = WinGoPrediction(
        forPeriod = null, side = null, probBig = 0.5, confidence = 0.5, level = ConfidenceLevel.VERY_LOW,
        signal = Signal.WAIT, agree = 0, voting = 0, totalModels = models.size, isCandidate = false,
        waitReason = reason, outputs = emptyList(), weights = emptyMap(), historySize = historySize, edge = edge
    )

    private companion object {
        const val MEASURED_CAP_MIN_SAMPLES = 50
    }
}
