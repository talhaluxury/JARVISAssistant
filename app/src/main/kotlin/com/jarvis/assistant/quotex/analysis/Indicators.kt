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

    /** Raw Bollinger bands (lower, middle, upper). NaN rows until [period] values exist. */
    fun bollingerBands(values: DoubleArray, period: Int = 20, width: Double = 2.0): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val lower = DoubleArray(values.size) { Double.NaN }
        val mid = DoubleArray(values.size) { Double.NaN }
        val upper = DoubleArray(values.size) { Double.NaN }
        if (values.size < period) return Triple(lower, mid, upper)
        for (i in period - 1 until values.size) {
            var sum = 0.0
            for (j in i - period + 1..i) sum += values[j]
            val mean = sum / period
            var sq = 0.0
            for (j in i - period + 1..i) sq += (values[j] - mean) * (values[j] - mean)
            val sd = sqrt(sq / period)
            mid[i] = mean
            lower[i] = mean - width * sd
            upper[i] = mean + width * sd
        }
        return Triple(lower, mid, upper)
    }

    /** MACD line, signal line and histogram, all EMA-based (12/26/9 by default). */
    fun macd(values: DoubleArray, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val fastEma = ema(values, fast)
        val slowEma = ema(values, slow)
        val line = DoubleArray(values.size) { fastEma[it] - slowEma[it] }
        val signal = ema(line, signalPeriod)
        val histogram = DoubleArray(values.size) { line[it] - signal[it] }
        return Triple(line, signal, histogram)
    }

    /** Wilder's ATR (average true range). NaN until [period] true ranges are available. */
    fun atr(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int = 14): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= period) return out
        val tr = DoubleArray(n)
        tr[0] = highs[0] - lows[0]
        for (i in 1 until n) {
            tr[i] = maxOf(highs[i] - lows[i], kotlin.math.abs(highs[i] - closes[i - 1]), kotlin.math.abs(lows[i] - closes[i - 1]))
        }
        var avg = 0.0
        for (i in 1..period) avg += tr[i]
        avg /= period
        out[period] = avg
        for (i in period + 1 until n) {
            avg = (avg * (period - 1) + tr[i]) / period
            out[i] = avg
        }
        return out
    }

    /** Stochastic %K and %D (smoothed). NaN until [period] highs/lows are available. */
    fun stochastic(
        highs: DoubleArray, lows: DoubleArray, closes: DoubleArray,
        period: Int = 14, smoothK: Int = 3, smoothD: Int = 3
    ): Pair<DoubleArray, DoubleArray> {
        val n = closes.size
        val rawK = DoubleArray(n) { Double.NaN }
        for (i in period - 1 until n) {
            var hh = Double.NEGATIVE_INFINITY
            var ll = Double.POSITIVE_INFINITY
            for (j in i - period + 1..i) {
                if (highs[j] > hh) hh = highs[j]
                if (lows[j] < ll) ll = lows[j]
            }
            rawK[i] = if (hh - ll <= 0.0) 50.0 else (closes[i] - ll) / (hh - ll) * 100.0
        }
        val k = smoothed(rawK, smoothK)
        val d = smoothed(k, smoothD)
        return Pair(k, d)
    }

    /** Simple moving average that skips NaN gaps at the start rather than propagating them. */
    private fun smoothed(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in values.indices) {
            val start = i - period + 1
            if (start < 0 || values[start].isNaN()) continue
            var sum = 0.0
            var ok = true
            for (j in start..i) {
                if (values[j].isNaN()) {
                    ok = false
                    break
                }
                sum += values[j]
            }
            if (ok) out[i] = sum / period
        }
        return out
    }

    /** Wilder's ADX (trend strength, 0-100, independent of direction). NaN until enough data exists. */
    fun adx(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int = 14): DoubleArray {
        val n = closes.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= period * 2) return out
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            // Moves that differ only by floating-point noise (1e-9 of the price) are ties: neither side gets directional movement.
            val eps = 1e-9 * kotlin.math.abs(closes[i - 1])
            plusDm[i] = if (upMove > downMove + eps && upMove > eps) upMove else 0.0
            minusDm[i] = if (downMove > upMove + eps && downMove > eps) downMove else 0.0
            tr[i] = maxOf(highs[i] - lows[i], kotlin.math.abs(highs[i] - closes[i - 1]), kotlin.math.abs(lows[i] - closes[i - 1]))
        }
        var smoothTr = 0.0
        var smoothPlusDm = 0.0
        var smoothMinusDm = 0.0
        for (i in 1..period) {
            smoothTr += tr[i]
            smoothPlusDm += plusDm[i]
            smoothMinusDm += minusDm[i]
        }
        val dx = DoubleArray(n) { Double.NaN }
        for (i in (period + 1) until n) {
            smoothTr = smoothTr - smoothTr / period + tr[i]
            smoothPlusDm = smoothPlusDm - smoothPlusDm / period + plusDm[i]
            smoothMinusDm = smoothMinusDm - smoothMinusDm / period + minusDm[i]
            val plusDi = if (smoothTr > 0.0) 100.0 * smoothPlusDm / smoothTr else 0.0
            val minusDi = if (smoothTr > 0.0) 100.0 * smoothMinusDm / smoothTr else 0.0
            val sum = plusDi + minusDi
            dx[i] = if (sum > 0.0) 100.0 * kotlin.math.abs(plusDi - minusDi) / sum else 0.0
        }
        var adxAvg = 0.0
        var started = false
        var count = 0
        for (i in (period + 1) until n) {
            if (dx[i].isNaN()) continue
            if (!started) {
                adxAvg += dx[i]
                count++
                if (count == period) {
                    adxAvg /= period
                    started = true
                    out[i] = adxAvg
                }
            } else {
                adxAvg = (adxAvg * (period - 1) + dx[i]) / period
                out[i] = adxAvg
            }
        }
        return out
    }
}

/** A chronological (oldest first) run of candles plus lazily computed indicators. */
class PriceSeries(val candles: List<Candle>) {
    val size: Int get() = candles.size
    val closes: DoubleArray = DoubleArray(candles.size) { candles[it].close }
    val highs: DoubleArray = DoubleArray(candles.size) { candles[it].high }
    val lows: DoubleArray = DoubleArray(candles.size) { candles[it].low }
    val up: BooleanArray = BooleanArray(candles.size) { candles[it].up }
    val ema9: DoubleArray by lazy { Indicators.ema(closes, 9) }
    val ema20: DoubleArray by lazy { Indicators.ema(closes, 20) }
    val ema21: DoubleArray by lazy { Indicators.ema(closes, 21) }
    val ema50: DoubleArray by lazy { Indicators.ema(closes, 50) }
    val ema100: DoubleArray by lazy { Indicators.ema(closes, 100) }
    val ema200: DoubleArray by lazy { Indicators.ema(closes, 200) }
    val rsi14: DoubleArray by lazy { Indicators.rsi(closes, 14) }
    val percentB: DoubleArray by lazy { Indicators.percentB(closes, 20, 2.0) }
    val bollinger: Triple<DoubleArray, DoubleArray, DoubleArray> by lazy { Indicators.bollingerBands(closes, 20, 2.0) }
    val macd: Triple<DoubleArray, DoubleArray, DoubleArray> by lazy { Indicators.macd(closes) }
    val atr14: DoubleArray by lazy { Indicators.atr(highs, lows, closes, 14) }
    val stochastic: Pair<DoubleArray, DoubleArray> by lazy { Indicators.stochastic(highs, lows, closes) }
    val adx14: DoubleArray by lazy { Indicators.adx(highs, lows, closes, 14) }

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
    private var ticks = 0

    /** Adds a reading. Returns the candle that just CLOSED (because this reading starts a new one), or null. */
    fun add(timeMs: Long, price: Double): Candle? {
        val thisBucket = timeMs / candleMs * candleMs
        var closed: Candle? = null
        if (bucket >= 0 && thisBucket != bucket) {
            closed = Candle(bucket, open, high, low, close, ticks)
        }
        if (bucket < 0 || thisBucket != bucket) {
            bucket = thisBucket
            open = price
            high = price
            low = price
            close = price
            ticks = 1
        } else {
            ticks++
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
