package com.jarvis.assistant.wingo.analysis

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.Fmt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Chronological (oldest first) view of past results. Models only ever receive a prefix of the
 * data that existed at prediction time - that is what keeps backtests free of look-ahead leakage.
 */
class RoundHistory(val numbers: List<Int>) {
    /** 1 = BIG, 0 = SMALL, oldest first. */
    val bits: IntArray = IntArray(numbers.size) { if (numbers[it] >= 5) 1 else 0 }
    val size: Int get() = bits.size

    fun bigCount(from: Int, toExclusive: Int): Int {
        var count = 0
        for (i in from until toExclusive) count += bits[i]
        return count
    }

    companion object {
        /** The last [cap] results before index [endExclusive] - never anything at or after it. */
        fun window(all: List<Int>, endExclusive: Int, cap: Int): RoundHistory {
            val start = (endExclusive - cap).coerceAtLeast(0)
            return RoundHistory(all.subList(start, endExclusive))
        }
    }
}

/** One independent sub-estimate inside a model (a window size, a context length, a similarity rule...). */
data class ModelPart(
    val name: String,
    val probBig: Double,
    val sampleSize: Int,
    val detail: String,
    val evidence: PatternEvidence? = null
) {
    /** How much this part has to say: sample size times how far it is from a coin flip. */
    val strength: Double get() = sampleSize * (probBig - 0.5) * (probBig - 0.5)
}

/** A model's opinion. [probBig] is its estimate of P(next round is BIG). */
data class ModelOutput(
    val modelName: String,
    val probBig: Double,
    val sampleSize: Int,
    val reason: String,
    val abstained: Boolean = false,
    /** Sub-estimates. The engine re-weights them by their own walk-forward record. */
    val parts: List<ModelPart> = emptyList(),
    /** Short numeric evidence, e.g. "26 BIG / 16 SMALL after 42 matches". */
    val evidence: String = ""
) {
    /** null when the model abstains or has no lean at all. */
    val prediction: BigSmall?
        get() = when {
            abstained -> null
            probBig > 0.5 + 1e-9 -> BigSmall.BIG
            probBig < 0.5 - 1e-9 -> BigSmall.SMALL
            else -> null
        }

    /** The model's own confidence in its lean (probability of the side it picked). */
    val confidence: Double
        get() = if (abstained) 0.5 else maxOf(probBig, 1.0 - probBig)

    /** Standard error of the estimate given its sample size (smaller is more reliable). */
    val uncertainty: Double
        get() = if (abstained || sampleSize <= 0) 0.5 else sqrt(probBig * (1.0 - probBig) / sampleSize)

    companion object {
        fun abstain(name: String, sampleSize: Int, reason: String) =
            ModelOutput(name, 0.5, sampleSize, reason, abstained = true)
    }
}

interface WinGoModel {
    val name: String
    fun predict(history: RoundHistory): ModelOutput
}

/** Laplace-smoothed frequency: never claims certainty from a small sample. */
internal fun laplace(big: Int, n: Int): Double = (big + 1.0) / (n + 2.0)

/** Stronger shrinkage towards 50% for pattern statistics (prior worth [prior] observations). */
internal fun shrunk(big: Double, n: Double, prior: Double = 10.0): Double = (big + prior / 2.0) / (n + prior)

/**
 * Combines a model's parts into one probability. Each part counts in proportion to its own strength
 * (sample size x distance from 50%) times its learned weight, so a context with plenty of support and a clear
 * lean beats both a rarely-seen long context and an ambiguous short one. Returns null without usable parts.
 */
fun combineParts(parts: List<ModelPart>, subWeight: (ModelPart) -> Double): Double? {
    var weightSum = 0.0
    var probSum = 0.0
    for (part in parts) {
        val w = subWeight(part) * part.strength
        if (w <= 0.0) continue
        weightSum += w
        probSum += w * part.probBig
    }
    if (weightSum > 1e-12) return probSum / weightSum
    val usable = parts.filter { subWeight(it) > 0.0 }
    if (usable.isEmpty()) return null
    return usable.sumOf { it.probBig } / usable.size
}

private fun sideLabel(bit: Int): String = if (bit == 1) "BIG" else "SMALL"

private fun modelFromParts(name: String, parts: List<ModelPart>, samples: Int, reason: String, evidence: String): ModelOutput {
    val p = combineParts(parts) { 1.0 } ?: 0.5
    return ModelOutput(name, p, samples, reason, parts = parts, evidence = evidence)
}

/** MODEL 1 - frequency over the most recent rounds. */
class RecentFrequencyModel(private val window: Int = 20) : WinGoModel {
    override val name: String = "Recent Frequency"

    override fun predict(history: RoundHistory): ModelOutput {
        if (history.size < window) return ModelOutput.abstain(name, history.size, "Needs $window rounds, have ${history.size}")
        val big = history.bigCount(history.size - window, history.size)
        return ModelOutput(
            name, laplace(big, window), window, "Last $window: $big BIG / ${window - big} SMALL",
            evidence = "$big BIG / ${window - big} SMALL in the last $window"
        )
    }
}

/** MODEL 2 - the same frequency over 5..500-round windows; every window is weighted by its own walk-forward record. */
class RollingWindowModel(private val windows: List<Int> = HistoryProfile.WINDOWS) : WinGoModel {
    override val name: String = "Rolling Windows"

    override fun predict(history: RoundHistory): ModelOutput {
        val usable = windows.filter { it <= history.size }
        if (usable.isEmpty()) return ModelOutput.abstain(name, history.size, "Needs ${windows.minOrNull() ?: 0} rounds, have ${history.size}")
        val parts = usable.map { w ->
            val big = history.bigCount(history.size - w, history.size)
            ModelPart("w$w", laplace(big, w), w, "last $w: $big BIG / ${w - big} SMALL")
        }
        val leaning = parts.count { it.probBig > 0.5 }
        val reason = parts.joinToString(" ") { "${it.name.drop(1)}:${(it.probBig * 100).toInt()}%" } + "; $leaning of ${parts.size} windows lean BIG"
        return modelFromParts(name, parts, usable.maxOf { it }, reason, "windows ${usable.joinToString("/")} checked")
    }
}

/** MODEL 3 - what has historically followed a streak of the current length (continuation vs reversal is decided by data). */
class StreakModel(private val maxStreak: Int = 5, private val minOccurrences: Int = 30) : WinGoModel {
    override val name: String = "Streak"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        if (n < 20) return ModelOutput.abstain(name, n, "Needs 20 rounds, have $n")
        val side = bits[n - 1]
        var streakLen = 0
        while (streakLen < n && bits[n - 1 - streakLen] == side) streakLen++
        var k = minOf(streakLen, maxStreak)
        while (k >= 1) {
            var occurrences = 0
            var continued = 0
            for (i in k until n) {
                var allSame = true
                for (j in (i - k) until i) {
                    if (bits[j] != side) {
                        allSame = false
                        break
                    }
                }
                if (allSame) {
                    occurrences++
                    if (bits[i] == side) continued++
                }
            }
            if (occurrences >= minOccurrences) {
                val pSame = laplace(continued, occurrences)
                val pBig = if (side == 1) pSame else 1.0 - pSame
                return ModelOutput(
                    name, pBig, occurrences,
                    "Current streak: ${streakLen}x ${sideLabel(side)}. After $k in a row the same side followed $continued of $occurrences times",
                    evidence = "$continued continued / ${occurrences - continued} reversed after $k in a row"
                )
            }
            k--
        }
        return ModelOutput.abstain(name, n, "Streak of $streakLen is too rare to measure")
    }
}

/** MODEL 4 - order-1/2/3 transitions (what followed the same last 1, 2 or 3 rounds); each order is weighted by its own record. */
class TransitionModel(
    private val orders: List<Int> = listOf(1, 2, 3),
    private val minOccurrences: Int = 30
) : WinGoModel {
    override val name: String = "Transition"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        val maxOrder = orders.maxOrNull() ?: return ModelOutput.abstain(name, n, "No orders configured")
        val counts = PatternMining.exactCounts(bits, maxOrder)
        val parts = ArrayList<ModelPart>()
        for (k in orders) {
            if (n <= k + minOccurrences) continue
            val occ = counts[0][k]
            val big = counts[1][k]
            if (occ < minOccurrences) continue
            val context = PatternMining.suffix(bits, k)
            parts.add(ModelPart("order$k", laplace(big, occ), occ, "after $context: BIG followed $big of $occ times"))
        }
        if (parts.isEmpty()) return ModelOutput.abstain(name, n, "Not enough repeats of the recent context")
        val best = parts.maxByOrNull { it.strength }!!
        return modelFromParts(name, parts, parts.maxOf { it.sampleSize }, best.detail, best.detail)
    }
}

/** MODEL 5 - recent frequency vs the long-run baseline (assumes a partial drift back; the walk-forward record decides if that is true). */
class DeviationModel(
    private val recentWindow: Int = 50,
    private val minHistory: Int = 100,
    private val reversion: Double = 0.3
) : WinGoModel {
    override val name: String = "Baseline Deviation"

    override fun predict(history: RoundHistory): ModelOutput {
        val n = history.size
        if (n < minHistory) return ModelOutput.abstain(name, n, "Needs $minHistory rounds, have $n")
        val baseline = laplace(history.bigCount(0, n), n)
        val recent = laplace(history.bigCount(n - recentWindow, n), recentWindow)
        val deviation = recent - baseline
        val se = sqrt(baseline * (1.0 - baseline) / recentWindow)
        val z = if (se > 0.0) deviation / se else 0.0
        val p = (baseline - reversion * deviation).coerceIn(0.01, 0.99)
        val reason = "Last $recentWindow BIG rate ${Fmt.pct(recent, 1)} vs baseline ${Fmt.pct(baseline, 1)} (z=${Fmt.num(z)})"
        return ModelOutput(name, p, recentWindow, reason, evidence = reason)
    }
}

/** MODEL 6 - exponentially weighted recent results (newest count most). */
class WeightedRecentModel(private val halfLife: Double = 10.0, private val window: Int = 100) : WinGoModel {
    override val name: String = "Weighted Recent"

    override fun predict(history: RoundHistory): ModelOutput {
        val n = history.size
        if (n < 20) return ModelOutput.abstain(name, n, "Needs 20 rounds, have $n")
        val decay = 0.5.pow(1.0 / halfLife)
        var weight = 1.0
        var weightSum = 0.0
        var weightBig = 0.0
        val used = minOf(window, n)
        for (age in 0 until used) {
            val bit = history.bits[n - 1 - age]
            weightSum += weight
            weightBig += weight * bit
            weight *= decay
        }
        val p = (weightBig + 1.0) / (weightSum + 2.0)
        val reason = "Recency-weighted (half-life ${halfLife.toInt()} rounds): ${Fmt.pct(p)} BIG"
        return ModelOutput(name, p, used, reason, evidence = reason)
    }
}

/**
 * MODEL 7 - exact sequence matching. The last 3..8 rounds are searched in the older history in ONE pass; every
 * context length that has enough support becomes its own part, and the engine weights each length by how well it
 * has predicted out of sample. Contexts seen fewer than [minOccurrences] times are not evidence.
 */
class PatternModel(
    private val minLength: Int = 3,
    private val maxLength: Int = 8,
    private val minOccurrences: Int = MIN_OCCURRENCES
) : WinGoModel {
    override val name: String = "Pattern"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        if (n < 60) return ModelOutput.abstain(name, n, "Needs 60 rounds, have $n")
        val counts = PatternMining.exactCounts(bits, maxLength)
        val parts = ArrayList<ModelPart>()
        for (m in minLength..maxLength) {
            if (n <= m) continue
            val occ = counts[0][m]
            val big = counts[1][m]
            if (occ < minOccurrences) continue
            val context = PatternMining.suffix(bits, m)
            val evidence = PatternEvidence(context, m, "EXACT", occ, big)
            val detail = "$context seen $occ times: $big BIG / ${occ - big} SMALL"
            parts.add(ModelPart("len$m", shrunk(big.toDouble(), occ.toDouble()), occ, detail, evidence))
        }
        if (parts.isEmpty()) {
            val longestWithSupport = (minLength..maxLength).lastOrNull { it < n && counts[0][it] >= 1 }
            val note = if (longestWithSupport == null) {
                "No earlier occurrence of the recent sequence"
            } else {
                "Pattern ${PatternMining.suffix(bits, 6.coerceAtMost(maxLength))}: only ${counts[0][6.coerceAtMost(maxLength)]} occurrences — insufficient pattern history"
            }
            return ModelOutput.abstain(name, counts[0][minLength], note)
        }
        val best = parts.maxByOrNull { it.strength }!!
        return modelFromParts(name, parts, best.sampleSize, best.detail, best.detail)
    }

    companion object {
        const val MIN_OCCURRENCES = 20
    }
}

/**
 * MODEL 8 - similar (not identical) sequences. Each rule (length, allowed differences) is its own part with its own
 * walk-forward record, so a rule that does not help out of sample loses weight and is switched off by the engine.
 * Closer matches count more (weight 1 / (1 + differences)^2).
 */
class SimilarPatternModel(
    private val rules: List<Pair<Int, Int>> = listOf(Pair(6, 1), Pair(8, 2), Pair(10, 3)),
    private val minMatches: Int = 30
) : WinGoModel {
    override val name: String = "Similar Pattern"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        if (n < 80) return ModelOutput.abstain(name, n, "Needs 80 rounds, have $n")
        val parts = ArrayList<ModelPart>()
        for ((length, distance) in rules) {
            if (n <= length) continue
            val s = PatternMining.similar(bits, length, distance)
            if (s.matches < minMatches) continue
            val context = PatternMining.suffix(bits, length)
            val evidence = PatternEvidence(context, length, "SIMILAR", s.matches, s.bigAfter, s.weightedMatches)
            val detail = "within $distance of $context: ${s.matches} matches (${s.exactMatches} exact), ${s.bigAfter} BIG / ${s.matches - s.bigAfter} SMALL"
            parts.add(ModelPart("sim${length}d$distance", shrunk(s.weightedBig, s.weightedMatches), s.matches, detail, evidence))
        }
        if (parts.isEmpty()) return ModelOutput.abstain(name, n, "Not enough similar sequences in the history")
        val best = parts.maxByOrNull { it.strength }!!
        return modelFromParts(name, parts, best.sampleSize, best.detail, best.detail)
    }
}

/**
 * MODEL 9 - sequence composition outcome. Ignores the exact order and asks: after windows that contained the same
 * number of BIG rounds and ended on the same side, what came next? (windows of 6, 8, 10 and 12 rounds)
 */
class SequenceOutcomeModel(
    private val windows: List<Int> = listOf(6, 8, 10, 12),
    private val minOccurrences: Int = 30
) : WinGoModel {
    override val name: String = "Sequence Outcome"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        if (n < 60) return ModelOutput.abstain(name, n, "Needs 60 rounds, have $n")
        val cum = IntArray(n + 1)
        for (i in 0 until n) cum[i + 1] = cum[i] + bits[i]
        val parts = ArrayList<ModelPart>()
        for (k in windows) {
            if (n <= k) continue
            val currentCount = cum[n] - cum[n - k]
            val currentLast = bits[n - 1]
            var occ = 0
            var big = 0
            for (j in k until n) { // window is rounds j-k..j-1, target is round j
                if (bits[j - 1] == currentLast && cum[j] - cum[j - k] == currentCount) {
                    occ++
                    big += bits[j]
                }
            }
            if (occ < minOccurrences) continue
            parts.add(
                ModelPart(
                    "seq$k", laplace(big, occ), occ,
                    "last $k had $currentCount BIG (ended on ${sideLabel(currentLast)}): BIG followed $big of $occ times"
                )
            )
        }
        if (parts.isEmpty()) return ModelOutput.abstain(name, n, "No comparable composition seen often enough")
        val best = parts.maxByOrNull { it.strength }!!
        return modelFromParts(name, parts, best.sampleSize, best.detail, best.detail)
    }
}

/**
 * MODEL 10 - regime / distribution. Classifies every stretch of 30 rounds by its BIG share (5 levels) and how much
 * it alternates (streaky / balanced / alternating), then looks up what followed earlier stretches in the same regime.
 */
class RegimeModel(private val window: Int = 30, private val minOccurrences: Int = 30) : WinGoModel {
    override val name: String = "Regime"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        if (n < window + 60) return ModelOutput.abstain(name, n, "Needs ${window + 60} rounds, have $n")
        val cum = IntArray(n + 1)
        val flips = IntArray(n + 1) // flips[i] = flips among adjacent pairs up to round i-1
        for (i in 0 until n) {
            cum[i + 1] = cum[i] + bits[i]
            flips[i + 1] = flips[i] + (if (i > 0 && bits[i] != bits[i - 1]) 1 else 0)
        }
        fun regimeAt(endExclusive: Int): Int {
            val big = cum[endExclusive] - cum[endExclusive - window]
            val flipCount = flips[endExclusive] - flips[endExclusive - window + 1]
            val share = big * 5 / (window + 1)
            val alt = if (flipCount < 12) 0 else if (flipCount <= 17) 1 else 2
            return share * 3 + alt
        }
        val current = regimeAt(n)
        var occ = 0
        var big = 0
        for (j in window..n - 1) {
            if (regimeAt(j) == current) {
                occ++
                big += bits[j]
            }
        }
        val shareNames = listOf("mostly SMALL", "SMALL-leaning", "balanced", "BIG-leaning", "mostly BIG")
        val altNames = listOf("streaky", "mixed", "alternating")
        val label = "${shareNames[current / 3]}, ${altNames[current % 3]}"
        if (occ < minOccurrences) return ModelOutput.abstain(name, occ, "Regime ($label) seen only $occ times")
        val reason = "Regime: $label. After such stretches BIG followed $big of $occ times"
        return ModelOutput(name, laplace(big, occ), occ, reason, evidence = reason)
    }
}

/** All ten models: the original seven plus similar-pattern, sequence-outcome and regime analysis. */
fun defaultWinGoModels(): List<WinGoModel> = listOf(
    RecentFrequencyModel(),
    RollingWindowModel(),
    StreakModel(),
    TransitionModel(),
    DeviationModel(),
    WeightedRecentModel(),
    PatternModel(),
    SimilarPatternModel(),
    SequenceOutcomeModel(),
    RegimeModel()
)
