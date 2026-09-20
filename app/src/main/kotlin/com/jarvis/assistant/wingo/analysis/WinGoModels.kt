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

/** A single model's opinion. [probBig] is its estimate of P(next round is BIG). */
data class ModelOutput(
    val modelName: String,
    val probBig: Double,
    val sampleSize: Int,
    val reason: String,
    val abstained: Boolean = false
) {
    /** null when the model abstains or has no lean at all. */
    val prediction: BigSmall?
        get() = when {
            abstained -> null
            probBig > 0.5 + 1e-9 -> BigSmall.BIG
            probBig < 0.5 - 1e-9 -> BigSmall.SMALL
            else -> null
        }

    val confidence: Double
        get() = if (abstained) 0.5 else maxOf(probBig, 1.0 - probBig)

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

private fun sideLabel(bit: Int): String = if (bit == 1) "BIG" else "SMALL"

private fun bitsToLetters(bits: IntArray, endExclusive: Int, length: Int): String {
    val sb = StringBuilder()
    for (i in (endExclusive - length) until endExclusive) sb.append(if (bits[i] == 1) 'B' else 'S')
    return sb.toString()
}

/** MODEL A - frequency over the most recent rounds. */
class RecentFrequencyModel(private val window: Int = 20) : WinGoModel {
    override val name: String = "Recent Frequency"

    override fun predict(history: RoundHistory): ModelOutput {
        if (history.size < window) return ModelOutput.abstain(name, history.size, "Needs $window rounds, have ${history.size}")
        val big = history.bigCount(history.size - window, history.size)
        return ModelOutput(name, laplace(big, window), window, "Last $window: $big BIG / ${window - big} SMALL")
    }
}

/** MODEL B - the same frequency read over 10/20/30/50/100/200-round windows, averaged. */
class RollingWindowModel(private val windows: List<Int> = listOf(10, 20, 30, 50, 100, 200)) : WinGoModel {
    override val name: String = "Rolling Windows"

    override fun predict(history: RoundHistory): ModelOutput {
        val usable = windows.filter { it <= history.size }
        if (usable.isEmpty()) return ModelOutput.abstain(name, history.size, "Needs ${windows.minOrNull() ?: 0} rounds, have ${history.size}")
        var sum = 0.0
        var bigLeaning = 0
        val parts = ArrayList<String>()
        for (w in usable) {
            val big = history.bigCount(history.size - w, history.size)
            val p = laplace(big, w)
            sum += p
            if (p > 0.5) bigLeaning++
            parts.add("$w:${big}B")
        }
        val reason = "Windows " + parts.joinToString(" ") + "; $bigLeaning of ${usable.size} lean BIG"
        return ModelOutput(name, sum / usable.size, usable.maxOf { it }, reason)
    }
}

/** MODEL C - what has historically followed a streak of the current length. */
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
                    "Current streak: ${streakLen}x ${sideLabel(side)}. After $k in a row the same side followed $continued of $occurrences times"
                )
            }
            k--
        }
        return ModelOutput.abstain(name, n, "Streak of $streakLen is too rare to measure")
    }
}

/** MODEL D - order-3/2/1 transition statistics (what followed the same last few results). */
class TransitionModel(
    private val orders: List<Int> = listOf(3, 2, 1),
    private val minOccurrences: Int = 30
) : WinGoModel {
    override val name: String = "Transition"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        for (k in orders) {
            if (n <= k + minOccurrences) continue
            var occurrences = 0
            var big = 0
            for (i in k until n) {
                var match = true
                for (j in 1..k) {
                    if (bits[i - j] != bits[n - j]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    occurrences++
                    big += bits[i]
                }
            }
            if (occurrences >= minOccurrences) {
                val context = bitsToLetters(bits, n, k)
                return ModelOutput(name, laplace(big, occurrences), occurrences, "After $context: BIG followed $big of $occurrences times")
            }
        }
        return ModelOutput.abstain(name, n, "Not enough repeats of the recent context")
    }
}

/**
 * MODEL E - recent frequency vs the long-run baseline. A recent excess of one side is treated as
 * a (partial) drift back towards the baseline. Whether that assumption helps at all is decided
 * by the walk-forward record, not by this class.
 */
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
        return ModelOutput(name, p, recentWindow, reason)
    }
}

/** MODEL F - exponentially weighted recent results (newest count most). */
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
        return ModelOutput(name, p, used, "Recency-weighted (half-life ${halfLife.toInt()} rounds): ${Fmt.pct(p)} BIG")
    }
}

/** MODEL G - longest recent B/S pattern (3-6 rounds) with enough past repeats, and what followed it. */
class PatternModel(
    private val minLength: Int = 3,
    private val maxLength: Int = 6,
    private val minOccurrences: Int = 8
) : WinGoModel {
    override val name: String = "Pattern"

    override fun predict(history: RoundHistory): ModelOutput {
        val bits = history.bits
        val n = history.size
        if (n < 60) return ModelOutput.abstain(name, n, "Needs 60 rounds, have $n")
        for (m in maxLength downTo minLength) {
            if (n <= m) continue
            var occurrences = 0
            var big = 0
            for (i in m until n) {
                var match = true
                for (j in 1..m) {
                    if (bits[i - j] != bits[n - j]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    occurrences++
                    big += bits[i]
                }
            }
            if (occurrences >= minOccurrences) {
                val letters = bitsToLetters(bits, n, m)
                val alternating = (1 until m).all { bits[n - it] != bits[n - it - 1] }
                val note = if (alternating) " (alternating)" else ""
                return ModelOutput(name, laplace(big, occurrences), occurrences, "Pattern $letters$note seen $occurrences times; BIG followed $big")
            }
        }
        return ModelOutput.abstain(name, n, "No recent pattern repeated often enough")
    }
}

/** MODELS A-G. (Model H, the backtesting engine, evaluates these rather than voting.) */
fun defaultWinGoModels(): List<WinGoModel> = listOf(
    RecentFrequencyModel(),
    RollingWindowModel(),
    StreakModel(),
    TransitionModel(),
    DeviationModel(),
    WeightedRecentModel(),
    PatternModel()
)
