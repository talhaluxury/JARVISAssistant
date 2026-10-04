package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.max
import kotlin.math.min

enum class FakeBreakKind { FAKE_BREAKOUT_UP, FAKE_BREAKDOWN, NONE }

/** [quality] is 0..100 (pattern quality, not a win probability). */
data class FakeBreakout(val kind: FakeBreakKind, val level: Double, val quality: Int, val detail: String) {
    companion object {
        val NONE = FakeBreakout(FakeBreakKind.NONE, Double.NaN, 0, "no fake breakout")
    }
}

/**
 * Resistance broken -> weak continuation -> long rejection wick -> price back below the level (and the mirror
 * for support). Uses ONLY the candles passed in (all closed); nothing after the newest candle is ever read.
 * A fake breakout AGAINST a setup is evidence for the "why not trade?" step; a fake breakdown/breakout in the
 * setup's favour is only supporting evidence, never a signal on its own.
 */
object FakeBreakoutDetector {

    fun detect(candles: List<Candle>, lookback: Int = 20, maxReturnCandles: Int = 3): FakeBreakout {
        if (candles.size < lookback + maxReturnCandles + 2) return FakeBreakout.NONE
        val o = DoubleArray(candles.size) { candles[it].open }
        val h = DoubleArray(candles.size) { candles[it].high }
        val l = DoubleArray(candles.size) { candles[it].low }
        val c = DoubleArray(candles.size) { candles[it].close }

        val up = scan(o, h, l, c, lookback, maxReturnCandles)
        // Mirror: negate prices so "support broken then reclaimed" becomes "resistance broken then lost".
        val down = scan(
            DoubleArray(o.size) { -o[it] }, DoubleArray(o.size) { -l[it] },
            DoubleArray(o.size) { -h[it] }, DoubleArray(o.size) { -c[it] },
            lookback, maxReturnCandles
        )
        return when {
            up != null && (down == null || up.quality >= down.quality) ->
                FakeBreakout(FakeBreakKind.FAKE_BREAKOUT_UP, up.level, up.quality, up.detail)
            down != null ->
                FakeBreakout(FakeBreakKind.FAKE_BREAKDOWN, -down.level, down.quality, down.detail)
            else -> FakeBreakout.NONE
        }
    }

    private class Hit(val level: Double, val quality: Int, val detail: String)

    private fun scan(o: DoubleArray, h: DoubleArray, l: DoubleArray, c: DoubleArray, lookback: Int, maxReturn: Int): Hit? {
        val n = c.size
        val last = n - 1
        var best: Hit? = null
        // j = the candle that poked above the level; it must be older than the newest candle (a return needs time).
        for (j in (last - maxReturn)..(last - 1)) {
            val from = j - lookback
            if (from < 0) continue
            var ref = Double.NEGATIVE_INFINITY
            var rangeSum = 0.0
            for (k in from until j) {
                ref = max(ref, h[k])
                rangeSum += h[k] - l[k]
            }
            val unit = rangeSum / lookback
            if (unit <= 0.0) continue
            if (h[j] <= ref + 0.05 * unit) continue // no real poke above the level

            // Weak continuation: no close since the poke got meaningfully beyond the level.
            var sustained = false
            for (k in j..(last - 1)) if (c[k] > ref + 0.6 * unit) sustained = true
            if (sustained) continue
            if (c[last] >= ref) continue // not back under the level yet

            val range = h[j] - l[j]
            val wick = h[j] - max(o[j], c[j])
            var q = 45
            if (range > 0.0 && wick >= 0.5 * range) q += 20
            q += min(20, (((ref - c[last]) / unit) * 20.0).toInt().coerceAtLeast(0))
            if (c[last] < o[last]) q += 10
            if (last - j <= 1) q += 5
            q = min(100, q)
            val hit = Hit(ref, q, "level poked then lost within ${last - j} candle(s), rejection wick ${if (range > 0) (wick / range * 100).toInt() else 0}% of range")
            if (best == null || hit.quality >= best.quality) best = hit
        }
        return best
    }
}
