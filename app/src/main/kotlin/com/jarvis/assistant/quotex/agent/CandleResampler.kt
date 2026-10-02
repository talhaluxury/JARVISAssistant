package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.Candle

/** Section 4: builds higher-timeframe candles from lower-timeframe ones, never including an unfinished bucket. */
object CandleResampler {

    /** Smallest positive spacing between recent candles, or null when it cannot be inferred. */
    fun inferCandleMs(candles: List<Candle>): Long? {
        if (candles.size < 2) return null
        var best = Long.MAX_VALUE
        val start = maxOf(1, candles.size - 30)
        for (i in start until candles.size) {
            val d = candles[i].openTimeMs - candles[i - 1].openTimeMs
            if (d > 0L && d < best) best = d
        }
        return if (best == Long.MAX_VALUE) null else best
    }

    /**
     * Groups [candles] into buckets of [factor] base candles. The final bucket is dropped when it is not
     * yet complete, so a higher-timeframe candle only appears once all of its parts are known (no look-ahead).
     */
    fun resample(candles: List<Candle>, factor: Int, baseMs: Long? = null): List<Candle> {
        if (factor <= 1) return candles
        val base = baseMs ?: inferCandleMs(candles) ?: return emptyList()
        val bucketMs = base * factor
        val out = ArrayList<Candle>()
        var bucketStart = Long.MIN_VALUE
        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var count = 0
        for (c in candles) {
            val b = Math.floorDiv(c.openTimeMs, bucketMs) * bucketMs
            if (b != bucketStart) {
                if (count >= factor) out.add(Candle(bucketStart, open, high, low, close))
                bucketStart = b
                open = c.open
                high = c.high
                low = c.low
                close = c.close
                count = 1
            } else {
                if (c.high > high) high = c.high
                if (c.low < low) low = c.low
                close = c.close
                count++
            }
        }
        // The trailing bucket is only emitted if it is complete; an in-progress one is deliberately left out.
        if (count >= factor) out.add(Candle(bucketStart, open, high, low, close))
        return out
    }
}
