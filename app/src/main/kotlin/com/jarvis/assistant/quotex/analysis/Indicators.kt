package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.max
import kotlin.math.sqrt

/** Causal indicators only: the value at index i depends on data up to i, never later. */
object Indicators {

    fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size)
        if (values.isEmpty()) return out
        val k = 2.0 / (period + 1)
        out[0] = values[0]
        for (i in 1 until values.size) out[i] = values[i] * k + out[i - 1] * (1 - k)
        return out
    }

    /** Wilder RSI. NaN until [period] changes are available. */
    fun rsi(values: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        out[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double = when {
        avgLoss == 0.0 && avgGain == 0.0 -> 50.0
        avgLoss == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    /** Bollinger %B: 0 = lower band, 1 = upper band. NaN until [period] values exist. */
    fun percentB(values: DoubleArray, period: Int = 20, width: Double = 2.0): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size < period) return out
        for (i in period - 1 until values.size) {
            var sum = 0.0
            for (j in i - period + 1..i) sum += values[j]
            val mean = sum / period
            var sq = 0.0
            for (j in i - period + 1..i) sq += (values[j] - mean) * (values[j] - mean)
            val sd = sqrt(sq / period)
            val upper = mean + width * sd
            val lower = mean - width * sd
            out[i] = if (upper - lower <= 0.0) 0.5 else (values[i] - lower) / (upper - lower)
        }
        return out
    }
}

/** A chronological (oldest first) run of candles plus lazily computed indicators. */
class PriceSeries(val candles: List<Candle>) {
    val size: Int get() = candles.size
    val closes: DoubleArray = DoubleArray(candles.size) { candles[it].close }
    val up: BooleanArray = BooleanArray(candles.size) { candles[it].up }
    val ema9: DoubleArray by lazy { Indicators.ema(closes, 9) }
    val ema21: DoubleArray by lazy { Indicators.ema(closes, 21) }
    val rsi14: DoubleArray by lazy { Indicators.rsi(closes, 14) }
    val percentB: DoubleArray by lazy { Indicators.percentB(closes, 20, 2.0) }

    companion object {
        /** The last [cap] candles before index [endExclusive] - never anything at or after it. */
        fun window(all: List<Candle>, endExclusive: Int, cap: Int): PriceSeries {
            val start = max(0, endExclusive - cap)
            return PriceSeries(all.subList(start, endExclusive))
        }
    }
}

/** Helpers for candle runs. */
object CandleRuns {
    /** The newest run in which consecutive candles are at most [maxGapCandles] candles apart. */
    fun contiguousTail(candles: List<Candle>, candleMs: Long, maxGapCandles: Int = 2): List<Candle> {
        if (candles.size < 2) return candles.toList()
        var start = candles.size - 1
        while (start > 0 && candles[start].openTimeMs - candles[start - 1].openTimeMs <= (maxGapCandles + 1) * candleMs) start--
        return candles.subList(start, candles.size).toList()
    }

    /** Number of places where more than one candle is missing between neighbours. */
    fun countGaps(candles: List<Candle>, candleMs: Long): Int {
        var gaps = 0
        for (i in 1 until candles.size) {
            if (candles[i].openTimeMs - candles[i - 1].openTimeMs > candleMs * 3 / 2) gaps++
        }
        return gaps
    }
}

/** Turns a stream of price readings into fixed-length candles. */
class CandleBuilder(private val candleSeconds: Int) {
    private val candleMs = candleSeconds * 1000L
    private var bucket = -1L
    private var open = 0.0
    private var high = 0.0
    private var low = 0.0
    private var close = 0.0

    /** Adds a reading. Returns the candle that just CLOSED (because this reading starts a new one), or null. */
    fun add(timeMs: Long, price: Double): Candle? {
        val thisBucket = timeMs / candleMs * candleMs
        var closed: Candle? = null
        if (bucket >= 0 && thisBucket != bucket) {
            closed = Candle(bucket, open, high, low, close)
        }
        if (bucket < 0 || thisBucket != bucket) {
            bucket = thisBucket
            open = price
            high = price
            low = price
            close = price
        } else {
            if (price > high) high = price
            if (price < low) low = price
            close = price
        }
        return closed
    }

    fun reset() {
        bucket = -1L
    }
}
