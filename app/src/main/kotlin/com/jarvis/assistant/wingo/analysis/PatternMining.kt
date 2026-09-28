package com.jarvis.assistant.wingo.analysis

import kotlin.math.sqrt

/** Wilson score interval for a proportion (default 90%). Honest uncertainty for small samples. */
fun wilsonInterval(successes: Int, n: Int, z: Double = 1.645): Pair<Double, Double> {
    if (n <= 0) return Pair(0.0, 1.0)
    val p = successes.toDouble() / n
    val z2 = z * z
    val denominator = 1.0 + z2 / n
    val centre = (p + z2 / (2.0 * n)) / denominator
    val margin = z * sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n)) / denominator
    return Pair((centre - margin).coerceAtLeast(0.0), (centre + margin).coerceAtMost(1.0))
}

/** What followed a historical context (exact or similar). Counts only include rounds that already existed. */
data class PatternEvidence(
    val context: String,
    val length: Int,
    /** "EXACT" or "SIMILAR". */
    val kind: String,
    val occurrences: Int,
    val bigAfter: Int,
    val weightedOccurrences: Double = occurrences.toDouble()
) {
    val smallAfter: Int get() = occurrences - bigAfter
    val bigShare: Double get() = if (occurrences == 0) 0.5 else bigAfter.toDouble() / occurrences
    val interval: Pair<Double, Double> get() = wilsonInterval(bigAfter, occurrences)
}

data class ContextCount(val context: String, val occurrences: Int, val bigAfter: Int) {
    val smallAfter: Int get() = occurrences - bigAfter
    val bigShare: Double get() = if (occurrences == 0) 0.5 else bigAfter.toDouble() / occurrences
}

data class SimilarCount(val matches: Int, val exactMatches: Int, val bigAfter: Int, val weightedMatches: Double, val weightedBig: Double)

object PatternMining {
    fun letters(bits: IntArray, from: Int, toExclusive: Int): String {
        val sb = StringBuilder()
        for (i in from until toExclusive) sb.append(if (bits[i] == 1) 'B' else 'S')
        return sb.toString()
    }

    fun suffix(bits: IntArray, length: Int): String = letters(bits, (bits.size - length).coerceAtLeast(0), bits.size)

    fun bitsOf(letters: String): IntArray = IntArray(letters.length) { if (letters[it] == 'B') 1 else 0 }

    /**
     * One pass over the history: for every context length m in 1..maxLen, how often the last m rounds occurred
     * earlier (followed by a known next round) and how often BIG followed. Index 0 is unused.
     */
    fun exactCounts(bits: IntArray, maxLen: Int): Array<IntArray> {
        val n = bits.size
        val occ = IntArray(maxLen + 1)
        val big = IntArray(maxLen + 1)
        for (i in 1 until n) { // target round i, context ends at i-1
            var l = 0
            while (l < maxLen && i - 1 - l >= 0 && bits[i - 1 - l] == bits[n - 1 - l]) l++
            for (m in 1..l) {
                occ[m]++
                big[m] += bits[i]
            }
        }
        return arrayOf(occ, big)
    }

    /** Occurrences of an arbitrary context (e.g. "BSSBBS") that were followed by a known next round. */
    fun countContext(bits: IntArray, context: String): ContextCount {
        val ctx = bitsOf(context)
        val m = ctx.size
        var occ = 0
        var big = 0
        if (m == 0) return ContextCount(context, 0, 0)
        for (i in m until bits.size) {
            var match = true
            for (j in 0 until m) {
                if (bits[i - m + j] != ctx[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                occ++
                big += bits[i]
            }
        }
        return ContextCount(context, occ, big)
    }

    /**
     * Similar contexts: past windows of [length] rounds that differ from the current one in at most
     * [maxDistance] positions. Closer matches count more: weight = 1 / (1 + distance)^2.
     */
    fun similar(bits: IntArray, length: Int, maxDistance: Int): SimilarCount {
        val n = bits.size
        var matches = 0
        var exact = 0
        var big = 0
        var wMatches = 0.0
        var wBig = 0.0
        if (n <= length) return SimilarCount(0, 0, 0, 0.0, 0.0)
        for (i in length until n) {
            var distance = 0
            for (j in 1..length) {
                if (bits[i - j] != bits[n - j]) {
                    distance++
                    if (distance > maxDistance) break
                }
            }
            if (distance > maxDistance) continue
            val w = 1.0 / ((1.0 + distance) * (1.0 + distance))
            matches++
            if (distance == 0) exact++
            big += bits[i]
            wMatches += w
            wBig += w * bits[i]
        }
        return SimilarCount(matches, exact, big, wMatches, wBig)
    }
}

/** Per-window description of recent and long-term behaviour (spec section 2). */
data class WindowStat(
    val window: Int,
    val big: Int,
    val small: Int,
    val longestBig: Int,
    val longestSmall: Int,
    /** Length of the current streak, capped to the window. */
    val currentStreak: Int,
    val currentSide: Char,
    /** Adjacent flips B->S or S->B inside the window (alternation). */
    val flips: Int,
    /** Places where a 3-round block is immediately repeated (e.g. BSB BSB). */
    val repeatedBlocks: Int
) {
    val bigPct: Double get() = if (big + small == 0) 0.0 else big.toDouble() / (big + small)
    val smallPct: Double get() = if (big + small == 0) 0.0 else small.toDouble() / (big + small)
}

object HistoryProfile {
    val WINDOWS = listOf(5, 10, 20, 30, 50, 100, 200, 500)

    /** Windows that fit the history, plus "everything" when more than 500 rounds exist. */
    fun windows(history: RoundHistory): List<WindowStat> {
        val sizes = ArrayList<Int>(WINDOWS.filter { it <= history.size })
        if (history.size > 500) sizes.add(history.size)
        return sizes.map { stat(history.bits, it) }
    }

    fun stat(bits: IntArray, window: Int): WindowStat {
        val n = bits.size
        val start = (n - window).coerceAtLeast(0)
        var big = 0
        var longestBig = 0
        var longestSmall = 0
        var runLen = 0
        var runSide = -1
        var flips = 0
        var repeated = 0
        for (i in start until n) {
            big += bits[i]
            if (bits[i] == runSide) {
                runLen++
            } else {
                runSide = bits[i]
                runLen = 1
                if (i > start) flips++
            }
            if (runSide == 1 && runLen > longestBig) longestBig = runLen
            if (runSide == 0 && runLen > longestSmall) longestSmall = runLen
            if (i - start >= 5 && bits[i] == bits[i - 3] && bits[i - 1] == bits[i - 4] && bits[i - 2] == bits[i - 5]) repeated++
        }
        return WindowStat(window, big, (n - start) - big, longestBig, longestSmall, runLen, if (runSide == 1) 'B' else 'S', flips, repeated)
    }
}

/** Transition / streak tables for display (spec sections 5 and 6). */
object TransitionTables {
    /** After every 1-, 2- and 3-round context (B, S, BB, BS, ... SSS): what followed. */
    fun transitions(history: RoundHistory): List<ContextCount> {
        val out = ArrayList<ContextCount>()
        for (k in 1..3) {
            for (code in 0 until (1 shl k)) {
                val context = (k - 1 downTo 0).joinToString("") { if ((code shr it) and 1 == 1) "B" else "S" }
                out.add(PatternMining.countContext(history.bits, context))
            }
        }
        return out
    }

    /** What followed streaks of length 1..5 of each side. */
    fun streaks(history: RoundHistory): List<ContextCount> {
        val out = ArrayList<ContextCount>()
        for (side in listOf('B', 'S')) {
            for (k in 1..5) out.add(PatternMining.countContext(history.bits, side.toString().repeat(k)))
        }
        return out
    }
}

/**
 * "Backtest this pattern": walk through every earlier occurrence of [context] in order; before each one, predict
 * that the side which followed the pattern MORE OFTEN in the EARLIER occurrences will follow again (needs
 * [minPrior] earlier occurrences), then reveal what actually followed. Only earlier occurrences are ever used.
 */
data class PatternBacktest(val context: String, val occurrences: Int, val predictions: Int, val correct: Int) {
    val accuracy: Double? get() = if (predictions == 0) null else correct.toDouble() / predictions
}

object PatternBacktester {
    fun runLen(bits: IntArray, context: String, minPrior: Int = 10): PatternBacktest {
        val ctx = PatternMining.bitsOf(context)
        val m = ctx.size
        var occ = 0
        var big = 0
        var predictions = 0
        var correct = 0
        if (m == 0) return PatternBacktest(context, 0, 0, 0)
        for (i in m until bits.size) {
            var match = true
            for (j in 0 until m) {
                if (bits[i - m + j] != ctx[j]) {
                    match = false
                    break
                }
            }
            if (!match) continue
            if (occ >= minPrior && big * 2 != occ) {
                val predictedBig = big * 2 > occ
                predictions++
                if (predictedBig == (bits[i] == 1)) correct++
            }
            occ++
            big += bits[i]
        }
        return PatternBacktest(context, occ, predictions, correct)
    }
}
