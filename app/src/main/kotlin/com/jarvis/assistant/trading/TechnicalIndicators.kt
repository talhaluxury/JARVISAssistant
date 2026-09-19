package com.jarvis.assistant.trading

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PHASE 2 — TECHNICAL ANALYSIS ENGINE (spec §4)
 *
 * Every function returns a `List<Double?>` the same length as its input, with `null` for
 * indices where the indicator isn't yet defined (not enough warm-up data) — this is what spec
 * §4 means by "indicators must not independently trigger trades": there's no `signal()` method
 * here at all, only raw values. A later phase (the confluence/signal engine) is the only thing
 * allowed to turn these numbers into a decision, and it will need aligned, nullable series
 * (not just "the latest value") to reason about crossovers and recent history.
 *
 * All functions are pure and side-effect-free so they're trivial to unit test against
 * hand-computed reference values.
 */
object TechnicalIndicators {

    fun sma(values: List<Double>, period: Int): List<Double?> {
        require(period > 0)
        val out = arrayOfNulls<Double>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out.toList()
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        require(period > 0)
        if (values.isEmpty()) return emptyList()
        val out = arrayOfNulls<Double>(values.size)
        val multiplier = 2.0 / (period + 1)
        // Seed with an SMA over the first `period` values, matching the conventional EMA
        // definition (spec lists EMA20/50/100/200 as standard indicators — no exotic seeding).
        if (values.size < period) return out.toList()
        var seed = 0.0
        for (i in 0 until period) seed += values[i]
        seed /= period
        out[period - 1] = seed
        var prev = seed
        for (i in period until values.size) {
            val next = (values[i] - prev) * multiplier + prev
            out[i] = next
            prev = next
        }
        return out.toList()
    }

    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        if (closes.size <= period) return List(closes.size) { null }
        val out = arrayOfNulls<Double>(closes.size)
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change >= 0) avgGain += change else avgLoss -= change
        }
        avgGain /= period
        avgLoss /= period
        out[period] = rsiFromAverages(avgGain, avgLoss)
        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiFromAverages(avgGain, avgLoss)
        }
        return out.toList()
    }

    private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    data class MacdResult(val macdLine: List<Double?>, val signalLine: List<Double?>, val histogram: List<Double?>)

    fun macd(closes: List<Double>, fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): MacdResult {
        val fastEma = ema(closes, fastPeriod)
        val slowEma = ema(closes, slowPeriod)
        val macdLine = closes.indices.map { i ->
            val f = fastEma[i]; val s = slowEma[i]
            if (f != null && s != null) f - s else null
        }
        // EMA of the MACD line itself, but only over the defined (non-null) tail — ema() needs
        // a dense Double list, so we track the offset back into the original index space.
        val firstDefined = macdLine.indexOfFirst { it != null }
        val signalLine = arrayOfNulls<Double>(closes.size)
        val histogram = arrayOfNulls<Double>(closes.size)
        if (firstDefined >= 0) {
            val dense = macdLine.subList(firstDefined, macdLine.size).map { it!! }
            val signalDense = ema(dense, signalPeriod)
            for (i in signalDense.indices) {
                val v = signalDense[i] ?: continue
                val originalIndex = firstDefined + i
                signalLine[originalIndex] = v
                histogram[originalIndex] = macdLine[originalIndex]!! - v
            }
        }
        return MacdResult(macdLine, signalLine.toList(), histogram.toList())
    }

    /** True Range / ATR (Wilder's smoothing) — needs full candles, not just closes, since it's
     * defined off high/low/prior-close. */
    fun atr(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (candles.size <= period) return List(candles.size) { null }
        val trueRanges = DoubleArray(candles.size)
        trueRanges[0] = candles[0].high - candles[0].low
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            trueRanges[i] = maxOf(c.high - c.low, abs(c.high - prevClose), abs(c.low - prevClose))
        }
        val out = arrayOfNulls<Double>(candles.size)
        var atrValue = trueRanges.slice(1..period).average() // Wilder seeds from TR[1..period]
        out[period] = atrValue
        for (i in period + 1 until candles.size) {
            atrValue = (atrValue * (period - 1) + trueRanges[i]) / period
            out[i] = atrValue
        }
        return out.toList()
    }

    /** ADX with +DI/-DI (Wilder). Returns ADX only; direction comes from the market structure
     * engine's own trend classification rather than duplicating +DI/-DI here, but they're
     * computed internally for correctness. */
    fun adx(candles: List<Candle>, period: Int = 14): List<Double?> {
        val n = candles.size
        if (n <= period * 2) return List(n) { null }
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val upMove = candles[i].high - candles[i - 1].high
            val downMove = candles[i - 1].low - candles[i].low
            plusDm[i] = if (upMove > downMove && upMove > 0) upMove else 0.0
            minusDm[i] = if (downMove > upMove && downMove > 0) downMove else 0.0
            val prevClose = candles[i - 1].close
            tr[i] = maxOf(candles[i].high - candles[i].low, abs(candles[i].high - prevClose), abs(candles[i].low - prevClose))
        }
        var smoothTr = tr.slice(1..period).sum()
        var smoothPlusDm = plusDm.slice(1..period).sum()
        var smoothMinusDm = minusDm.slice(1..period).sum()
        val dx = DoubleArray(n)
        fun diPair(): Pair<Double, Double> {
            val plusDi = if (smoothTr == 0.0) 0.0 else 100.0 * smoothPlusDm / smoothTr
            val minusDi = if (smoothTr == 0.0) 0.0 else 100.0 * smoothMinusDm / smoothTr
            return plusDi to minusDi
        }
        val (p0, m0) = diPair()
        dx[period] = if (p0 + m0 == 0.0) 0.0 else 100.0 * abs(p0 - m0) / (p0 + m0)
        for (i in period + 1 until n) {
            smoothTr = smoothTr - smoothTr / period + tr[i]
            smoothPlusDm = smoothPlusDm - smoothPlusDm / period + plusDm[i]
            smoothMinusDm = smoothMinusDm - smoothMinusDm / period + minusDm[i]
            val (p, m) = diPair()
            dx[i] = if (p + m == 0.0) 0.0 else 100.0 * abs(p - m) / (p + m)
        }
        val out = arrayOfNulls<Double>(n)
        val adxSeedEnd = period * 2
        if (adxSeedEnd < n) {
            var adxValue = dx.slice(period..adxSeedEnd).average()
            out[adxSeedEnd] = adxValue
            for (i in adxSeedEnd + 1 until n) {
                adxValue = (adxValue * (period - 1) + dx[i]) / period
                out[i] = adxValue
            }
        }
        return out.toList()
    }

    data class BollingerBands(val middle: List<Double?>, val upper: List<Double?>, val lower: List<Double?>)

    fun bollingerBands(closes: List<Double>, period: Int = 20, stdDevMultiplier: Double = 2.0): BollingerBands {
        val middle = sma(closes, period)
        val upper = arrayOfNulls<Double>(closes.size)
        val lower = arrayOfNulls<Double>(closes.size)
        for (i in closes.indices) {
            val mid = middle[i] ?: continue
            var sumSq = 0.0
            for (j in (i - period + 1)..i) sumSq += (closes[j] - mid) * (closes[j] - mid)
            val stdDev = sqrt(sumSq / period)
            upper[i] = mid + stdDevMultiplier * stdDev
            lower[i] = mid - stdDevMultiplier * stdDev
        }
        return BollingerBands(middle, upper.toList(), lower.toList())
    }

    data class Stochastic(val k: List<Double?>, val d: List<Double?>)

    fun stochastic(candles: List<Candle>, kPeriod: Int = 14, dPeriod: Int = 3): Stochastic {
        val n = candles.size
        val kValues = arrayOfNulls<Double>(n)
        for (i in candles.indices) {
            if (i < kPeriod - 1) continue
            var highest = Double.NEGATIVE_INFINITY
            var lowest = Double.POSITIVE_INFINITY
            for (j in (i - kPeriod + 1)..i) {
                highest = max(highest, candles[j].high)
                lowest = min(lowest, candles[j].low)
            }
            val range = highest - lowest
            kValues[i] = if (range == 0.0) 50.0 else 100.0 * (candles[i].close - lowest) / range
        }
        val kDense = kValues.toList()
        val dValues = arrayOfNulls<Double>(n)
        for (i in candles.indices) {
            if (i < kPeriod - 1 + dPeriod - 1) continue
            val window = (i - dPeriod + 1..i).mapNotNull { kDense[it] }
            if (window.size == dPeriod) dValues[i] = window.average()
        }
        return Stochastic(kDense, dValues.toList())
    }

    /** VWAP "where applicable" per spec §4 — requires volume, which the demo feed and many
     * retail Forex venues don't provide. Returns an all-null series (rather than throwing) when
     * any candle lacks volume, so callers can treat "not applicable here" as a normal, expected
     * outcome instead of an error. Resets cumulative sums at the start of the list (caller is
     * responsible for passing exactly one session's candles if session-anchored VWAP is wanted). */
    fun vwap(candles: List<Candle>): List<Double?> {
        if (candles.any { it.volume == null }) return List(candles.size) { null }
        val out = arrayOfNulls<Double>(candles.size)
        var cumulativePv = 0.0
        var cumulativeVolume = 0.0
        for (i in candles.indices) {
            val c = candles[i]
            val typicalPrice = (c.high + c.low + c.close) / 3.0
            cumulativePv += typicalPrice * c.volume!!
            cumulativeVolume += c.volume
            out[i] = if (cumulativeVolume > 0) cumulativePv / cumulativeVolume else null
        }
        return out.toList()
    }
}
