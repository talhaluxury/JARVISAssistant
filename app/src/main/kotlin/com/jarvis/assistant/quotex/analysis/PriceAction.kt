package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Section 7. [bias] is +1 bullish, -1 bearish, 0 neutral/indecision. */
enum class CandlePattern(val label: String, val bias: Int) {
    DOJI("Doji", 0),
    INSIDE_BAR("Inside bar", 0),
    HAMMER("Hammer", 1),
    SHOOTING_STAR("Shooting star", -1),
    BULLISH_PIN_BAR("Bullish pin bar", 1),
    BEARISH_PIN_BAR("Bearish pin bar", -1),
    BULLISH_ENGULFING("Bullish engulfing", 1),
    BEARISH_ENGULFING("Bearish engulfing", -1),
    MORNING_STAR("Morning star", 1),
    EVENING_STAR("Evening star", -1),
    STRONG_BULLISH_CANDLE("Strong bullish momentum candle", 1),
    STRONG_BEARISH_CANDLE("Strong bearish momentum candle", -1)
}

/** A detected pattern. It only counts when [contextValid] - a pattern floating in the middle of nowhere is noise. */
data class PatternHit(val pattern: CandlePattern, val contextValid: Boolean, val note: String)

object PriceActionEngine {

    private class Shape(val c: Candle) {
        val range = c.high - c.low
        val body = abs(c.close - c.open)
        val upper = c.high - max(c.open, c.close)
        val lower = min(c.open, c.close) - c.low
        val bullish = c.close > c.open
        val bearish = c.close < c.open
    }

    /** Patterns on the NEWEST candle of [series], each with a verdict on whether its market context supports it. */
    fun detect(series: PriceSeries, swings: List<Swing>, trend: TrendState): List<PatternHit> {
        val i = series.size - 1
        if (i < 1) return emptyList()
        val cur = Shape(series.candles[i])
        val prev = Shape(series.candles[i - 1])
        if (cur.range <= 0.0) return emptyList()
        val atr = series.atr14[i]
        val atrOk = !atr.isNaN() && atr > 0.0

        val nearSupport = atrOk && swings.any { !it.isHigh && it.index < i && abs(it.price - cur.c.low) <= atr }
        val nearResistance = atrOk && swings.any { it.isHigh && it.index < i && abs(it.price - cur.c.high) <= atr }
        val downish = trend == TrendState.STRONG_DOWN || trend == TrendState.WEAK_DOWN
        val upish = trend == TrendState.STRONG_UP || trend == TrendState.WEAK_UP

        val hits = ArrayList<PatternHit>()

        if (cur.body <= 0.1 * cur.range) {
            hits.add(PatternHit(CandlePattern.DOJI, false, "indecision candle, body ${pct(cur.body, cur.range)} of range"))
        }
        if (cur.c.high < prev.c.high && cur.c.low > prev.c.low) {
            hits.add(PatternHit(CandlePattern.INSIDE_BAR, false, "range contained inside the previous candle"))
        }

        // Long lower wick: pin bar / hammer. Bullish reversal only counts at support and never against a strong downtrend.
        if (cur.lower >= 0.6 * cur.range && cur.body <= 0.3 * cur.range) {
            val valid = nearSupport && trend != TrendState.STRONG_DOWN
            val pattern = if (downish) CandlePattern.HAMMER else CandlePattern.BULLISH_PIN_BAR
            hits.add(PatternHit(pattern, valid, "lower wick ${pct(cur.lower, cur.range)} of range" + if (nearSupport) ", at support" else ", not at support"))
        }
        if (cur.upper >= 0.6 * cur.range && cur.body <= 0.3 * cur.range) {
            val valid = nearResistance && trend != TrendState.STRONG_UP
            val pattern = if (upish) CandlePattern.SHOOTING_STAR else CandlePattern.BEARISH_PIN_BAR
            hits.add(PatternHit(pattern, valid, "upper wick ${pct(cur.upper, cur.range)} of range" + if (nearResistance) ", at resistance" else ", not at resistance"))
        }

        if (prev.bearish && cur.bullish && cur.body > prev.body &&
            cur.c.open <= prev.c.close && cur.c.close >= prev.c.open
        ) {
            hits.add(PatternHit(CandlePattern.BULLISH_ENGULFING, nearSupport && trend != TrendState.STRONG_DOWN, "bullish candle engulfs the previous bearish body"))
        }
        if (prev.bullish && cur.bearish && cur.body > prev.body &&
            cur.c.open >= prev.c.close && cur.c.close <= prev.c.open
        ) {
            hits.add(PatternHit(CandlePattern.BEARISH_ENGULFING, nearResistance && trend != TrendState.STRONG_UP, "bearish candle engulfs the previous bullish body"))
        }

        if (i >= 2) {
            val first = Shape(series.candles[i - 2])
            val star = prev
            val starSmall = star.range > 0.0 && star.body <= 0.3 * star.range
            if (first.bearish && first.range > 0.0 && first.body >= 0.5 * first.range && starSmall &&
                cur.bullish && cur.c.close > (first.c.open + first.c.close) / 2.0
            ) {
                hits.add(PatternHit(CandlePattern.MORNING_STAR, nearSupport && trend != TrendState.STRONG_DOWN, "three-candle bullish reversal shape"))
            }
            if (first.bullish && first.range > 0.0 && first.body >= 0.5 * first.range && starSmall &&
                cur.bearish && cur.c.close < (first.c.open + first.c.close) / 2.0
            ) {
                hits.add(PatternHit(CandlePattern.EVENING_STAR, nearResistance && trend != TrendState.STRONG_UP, "three-candle bearish reversal shape"))
            }
        }

        if (atrOk && cur.body >= 0.7 * cur.range && cur.range >= 1.2 * atr) {
            if (cur.bullish) hits.add(PatternHit(CandlePattern.STRONG_BULLISH_CANDLE, upish, "large body, range ${"%.1f".format(cur.range / atr)}x ATR"))
            if (cur.bearish) hits.add(PatternHit(CandlePattern.STRONG_BEARISH_CANDLE, downish, "large body, range ${"%.1f".format(cur.range / atr)}x ATR"))
        }
        return hits
    }

    private fun pct(part: Double, whole: Double): String = "${(part / whole * 100).toInt()}%"
}
