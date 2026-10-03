package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs

/**
 * Builds a long candle history out of several screenshots of the chart scrolled further and further into the past.
 *
 * The first frame (the live chart) seeds the history with correct open times. Every later frame shows older candles
 * and overlaps the previous one; the overlap is found by comparing real open/close values, and only candles older
 * than everything already known are added. If the overlap is missing, too short or ambiguous the frame is rejected -
 * a candle is never placed on a guess.
 */
class HistoryStitcher(
    private val candleMs: Long,
    private val tolerance: Double = 0.00005,
    private val minOverlap: Int = 4
) {
    enum class Result { SEEDED, EXTENDED, NO_NEW_CANDLES, NO_OVERLAP, AMBIGUOUS }

    private val known = ArrayList<Candle>() // oldest -> newest

    val candles: List<Candle> get() = known.toList()
    val size: Int get() = known.size

    /** The live frame. [frame] is oldest -> newest and its last candle is the one still forming. */
    fun seed(frame: List<Candle>): Result {
        known.clear()
        known.addAll(frame)
        return Result.SEEDED
    }

    private fun same(a: Candle, b: Candle): Boolean {
        val ref = maxOf(abs(b.close), 1e-9)
        val tol = ref * tolerance
        return abs(a.open - b.open) <= tol && abs(a.close - b.close) <= tol
    }

    /** [frame] is oldest -> newest (times ignored). Returns what happened; [candles] has grown on EXTENDED. */
    fun add(frame: List<Candle>): Result {
        if (known.size < minOverlap + 1 || frame.size < minOverlap) return Result.NO_OVERLAP
        // The newest known candle is still forming and may have changed since it was read: never match on it.
        val stable = known.subList(0, known.size - 1)
        val matches = ArrayList<Int>() // s = index in [stable] that frame[0] lines up with
        for (s in -(frame.size - minOverlap) until stable.size - minOverlap + 1) {
            var overlap = 0
            var ok = true
            for (i in frame.indices) {
                val j = s + i
                if (j < 0 || j >= stable.size) continue
                overlap++
                if (!same(frame[i], stable[j])) { ok = false; break }
            }
            if (ok && overlap >= minOverlap) matches.add(s)
        }
        if (matches.isEmpty()) return Result.NO_OVERLAP
        if (matches.size > 1) return Result.AMBIGUOUS
        val s = matches[0]
        if (s >= 0) return Result.NO_NEW_CANDLES // frame lies entirely inside what is already known
        val older = -s // frame[0 until older] are older than the oldest known candle
        val firstTime = known.first().openTimeMs
        val fresh = (0 until older).map { i ->
            val c = frame[i]
            Candle(firstTime - (older - i) * candleMs, c.open, c.high, c.low, c.close)
        }
        known.addAll(0, fresh)
        return Result.EXTENDED
    }
}
