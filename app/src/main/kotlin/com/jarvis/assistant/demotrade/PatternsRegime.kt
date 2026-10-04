package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A candlestick formation. [bias] is +1 bullish, -1 bearish, 0 neutral. It only ever contributes to the score. */
data class Pattern(val name: String, val bias: Int, val strength: Double)

object PatternDetector {

    private class Shape(c: Candle) {
        val body = abs(c.close - c.open)
        val range = c.high - c.low
        val upperWick = c.high - max(c.open, c.close)
        val lowerWick = min(c.open, c.close) - c.low
        val bull = c.close > c.open
        val bear = c.close < c.open
    }

    fun detect(ind: IndicatorSet): List<Pattern> {
        val n = ind.n
        if (n < 12) return emptyList()
        val cur = ind.candles[n - 1]
        val prev = ind.candles[n - 2]
        val prev2 = ind.candles[n - 3]
        val s = Shape(cur)
        val p = Shape(prev)
        val p2 = Shape(prev2)
        val atr = ind.atrNow
        if (s.range <= 0.0 || atr.isNaN() || atr <= 0.0) return emptyList()

        val out = ArrayList<Pattern>()
        val downBefore = ind.close[n - 5] > ind.close[n - 2] && ind.close[n - 4] > ind.close[n - 2]
        val upBefore = ind.close[n - 5] < ind.close[n - 2] && ind.close[n - 4] < ind.close[n - 2]

        if (s.body <= 0.1 * s.range) out.add(Pattern("Doji", 0, 0.3))

        if (s.body > 0.0 && s.lowerWick >= 2.0 * s.body && s.upperWick <= 0.3 * s.range && downBefore) {
            out.add(Pattern("Hammer", 1, 0.7))
        }
        if (s.body > 0.0 && s.upperWick >= 2.0 * s.body && s.lowerWick <= 0.3 * s.range && downBefore) {
            out.add(Pattern("Inverted hammer", 1, 0.5))
        }
        if (s.body > 0.0 && s.upperWick >= 2.0 * s.body && s.lowerWick <= 0.3 * s.range && upBefore) {
            out.add(Pattern("Shooting star", -1, 0.7))
        }
        if (s.lowerWick >= 0.66 * s.range && s.body <= 0.25 * s.range) out.add(Pattern("Bullish pin bar", 1, 0.7))
        if (s.upperWick >= 0.66 * s.range && s.body <= 0.25 * s.range) out.add(Pattern("Bearish pin bar", -1, 0.7))

        if (p.bear && s.bull && cur.open <= prev.close && cur.close >= prev.open && s.body > p.body) {
            out.add(Pattern("Bullish engulfing", 1, 0.8))
        }
        if (p.bull && s.bear && cur.open >= prev.close && cur.close <= prev.open && s.body > p.body) {
            out.add(Pattern("Bearish engulfing", -1, 0.8))
        }

        if (p2.bear && p2.body >= 0.5 * p2.range && p.body <= 0.3 * p2.body && s.bull &&
            cur.close > (prev2.open + prev2.close) / 2.0) {
            out.add(Pattern("Morning star", 1, 0.85))
        }
        if (p2.bull && p2.body >= 0.5 * p2.range && p.body <= 0.3 * p2.body && s.bear &&
            cur.close < (prev2.open + prev2.close) / 2.0) {
            out.add(Pattern("Evening star", -1, 0.85))
        }

        if (cur.high < prev.high && cur.low > prev.low) out.add(Pattern("Inside bar", 0, 0.3))

        if (s.body >= 0.7 * s.range && s.body >= 1.2 * atr) {
            if (s.bull) out.add(Pattern("Strong bullish candle", 1, 0.6)) else if (s.bear) out.add(Pattern("Strong bearish candle", -1, 0.6))
        }

        if (s.lowerWick >= 2.0 * max(s.body, 1e-12) && s.lowerWick >= 0.5 * s.range && s.lowerWick >= 0.5 * atr) {
            out.add(Pattern("Long lower wick rejection", 1, 0.6))
        }
        if (s.upperWick >= 2.0 * max(s.body, 1e-12) && s.upperWick >= 0.5 * s.range && s.upperWick >= 0.5 * atr) {
            out.add(Pattern("Long upper wick rejection", -1, 0.6))
        }

        if (n >= 14) {
            var priorHigh = Double.NEGATIVE_INFINITY
            var priorLow = Double.POSITIVE_INFINITY
            for (i in n - 11 until n - 1) { priorHigh = max(priorHigh, ind.high[i]); priorLow = min(priorLow, ind.low[i]) }
            if (cur.close > priorHigh && s.body >= 0.6 * s.range) out.add(Pattern("Bullish breakout candle", 1, 0.7))
            if (cur.close < priorLow && s.body >= 0.6 * s.range) out.add(Pattern("Bearish breakout candle", -1, 0.7))
        }
        return out
    }

    /** Net pattern pressure in -1..1 (strongest bullish pattern minus strongest bearish one). */
    fun net(patterns: List<Pattern>): Double {
        val bull = patterns.filter { it.bias > 0 }.maxOfOrNull { it.strength } ?: 0.0
        val bear = patterns.filter { it.bias < 0 }.maxOfOrNull { it.strength } ?: 0.0
        return (bull - bear).coerceIn(-1.0, 1.0)
    }
}

data class RegimeResult(val regime: Regime, val direction: Dir, val reasons: List<String>)

object RegimeDetector {

    fun detect(ind: IndicatorSet): RegimeResult {
        val n = ind.n
        if (n < 55) return RegimeResult(Regime.UNCERTAIN, Dir.WAIT, listOf("not enough candles for regime detection"))
        val e9 = ind.at(ind.ema9)
        val e21 = ind.at(ind.ema21)
        val e50 = ind.at(ind.ema50)
        val adx = ind.at(ind.adx14.adx)
        val atr = ind.atrNow
        val ratio = ind.atrRatio()
        if (e9.isNaN() || e21.isNaN() || e50.isNaN() || adx.isNaN() || atr.isNaN() || ratio.isNaN()) {
            return RegimeResult(Regime.UNCERTAIN, Dir.WAIT, listOf("indicators not ready"))
        }
        val price = ind.price
        val adxPrev = ind.at(ind.adx14.adx, 3)

        if (ratio >= 1.8) {
            return RegimeResult(Regime.HIGH_VOLATILITY, Dir.WAIT, listOf("ATR is ${fmt(ratio)}x its normal level"))
        }

        var priorHigh = Double.NEGATIVE_INFINITY
        var priorLow = Double.POSITIVE_INFINITY
        for (i in n - 21 until n - 1) { priorHigh = max(priorHigh, ind.high[i]); priorLow = min(priorLow, ind.low[i]) }
        val lastRange = ind.high[n - 1] - ind.low[n - 1]
        val expansion = lastRange >= 1.2 * atr
        if (price > priorHigh && expansion) {
            return RegimeResult(Regime.BREAKOUT, Dir.CALL, listOf("close above the 20-candle high with range expansion"))
        }
        if (price < priorLow && expansion) {
            return RegimeResult(Regime.BREAKOUT, Dir.PUT, listOf("close below the 20-candle low with range expansion"))
        }

        val stackUp = e9 > e21 && e21 > e50
        val stackDown = e9 < e21 && e21 < e50
        if (stackUp && adx >= 22.0 && price > e21) {
            return RegimeResult(Regime.TREND_UP, Dir.CALL, listOf("EMA 9>21>50, ADX ${fmt(adx)}"))
        }
        if (stackDown && adx >= 22.0 && price < e21) {
            return RegimeResult(Regime.TREND_DOWN, Dir.PUT, listOf("EMA 9<21<50, ADX ${fmt(adx)}"))
        }
        if (ratio <= 0.6 && adx < 20.0) {
            return RegimeResult(Regime.LOW_VOLATILITY, Dir.WAIT, listOf("ATR only ${fmt(ratio)}x normal and ADX ${fmt(adx)}"))
        }
        if (adx < 20.0) {
            val trending = !adxPrev.isNaN() && adx > adxPrev + 3.0
            if (!trending) return RegimeResult(Regime.RANGE, Dir.WAIT, listOf("ADX ${fmt(adx)} below 20: no directional trend"))
        }
        return RegimeResult(Regime.UNCERTAIN, Dir.WAIT, listOf("mixed signals (ADX ${fmt(adx)})"))
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}

/** Higher / middle / lower timeframe agreement, built only from candles that really exist (never invented). */
data class MtfResult(
    val available: Boolean,
    val lower: Dir,
    val middle: Dir,
    val higher: Dir,
    val alignment: Double,
    val notes: List<String>
) {
    /** The entry would trade against a known higher-timeframe trend. */
    fun vetoes(dir: Dir): Boolean = available && higher != Dir.WAIT && dir != Dir.WAIT && higher == dir.opposite()

    val summary: String
        get() = if (!available) "multi-timeframe: not enough history" else "higher ${higher.name} / middle ${middle.name} / lower ${lower.name}"
}

object MultiTimeframe {
    const val MIN_BARS = 30

    fun resample(candles: List<Candle>, candleMs: Long, factor: Int): List<Candle> {
        if (factor <= 1 || candleMs <= 0L || candles.isEmpty()) return candles
        val bucketMs = candleMs * factor
        val out = ArrayList<Candle>()
        var bucket = Long.MIN_VALUE
        var group = ArrayList<Candle>()
        fun flush() {
            if (group.size == factor) {
                out.add(
                    Candle(
                        openTimeMs = bucket,
                        open = group.first().open,
                        high = group.maxOf { it.high },
                        low = group.minOf { it.low },
                        close = group.last().close
                    )
                )
            }
            group = ArrayList()
        }
        for (c in candles) {
            val b = Math.floorDiv(c.openTimeMs, bucketMs) * bucketMs
            if (b != bucket) {
                if (bucket != Long.MIN_VALUE) flush()
                bucket = b
            }
            group.add(c)
        }
        flush() // an incomplete last bucket is dropped (size != factor), so no partial bar leaks in
        return out
    }

    fun trendOf(candles: List<Candle>): Dir {
        if (candles.size < MIN_BARS) return Dir.WAIT
        val close = DoubleArray(candles.size) { candles[it].close }
        val e9 = TA.ema(close, 9)
        val e21 = TA.ema(close, 21)
        val i = close.size - 1
        if (e9[i].isNaN() || e21[i].isNaN() || e21[i - 5].isNaN()) return Dir.WAIT
        return when {
            e9[i] > e21[i] && close[i] > e21[i] && e21[i] > e21[i - 5] -> Dir.CALL
            e9[i] < e21[i] && close[i] < e21[i] && e21[i] < e21[i - 5] -> Dir.PUT
            else -> Dir.WAIT
        }
    }

    fun analyze(candles: List<Candle>, candleMs: Long, enabled: Boolean): MtfResult {
        if (!enabled) return MtfResult(false, Dir.WAIT, Dir.WAIT, Dir.WAIT, 0.0, listOf("multi-timeframe analysis is off"))
        val lowerSeries = candles
        val middleSeries = resample(candles, candleMs, 5)
        val higherSeries = resample(candles, candleMs, 15)
        val lower = trendOf(lowerSeries)
        val middleOk = middleSeries.size >= MIN_BARS
        val higherOk = higherSeries.size >= MIN_BARS
        val middle = if (middleOk) trendOf(middleSeries) else Dir.WAIT
        val higher = if (higherOk) trendOf(higherSeries) else Dir.WAIT
        if (!middleOk && !higherOk) {
            return MtfResult(false, lower, Dir.WAIT, Dir.WAIT, 0.0, listOf("not enough history for higher timeframes (need $MIN_BARS bars of 5x / 15x candles)"))
        }
        var weightSum = 1.0
        var total = lower.sign * 1.0
        if (middleOk) { weightSum += 1.5; total += middle.sign * 1.5 }
        if (higherOk) { weightSum += 2.0; total += higher.sign * 2.0 }
        val notes = ArrayList<String>()
        if (higherOk && higher != Dir.WAIT && lower != Dir.WAIT && higher != lower) notes.add("higher and lower timeframe disagree")
        return MtfResult(true, lower, middle, higher, (total / weightSum).coerceIn(-1.0, 1.0), notes)
    }
}
