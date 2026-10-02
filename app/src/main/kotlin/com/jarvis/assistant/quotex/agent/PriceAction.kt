package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.PriceSeries
import kotlin.math.abs

enum class CandlePattern {
    DOJI, HAMMER, SHOOTING_STAR, BULLISH_ENGULFING, BEARISH_ENGULFING, PIN_BAR_BULL, PIN_BAR_BEAR,
    INSIDE_BAR, MORNING_STAR, EVENING_STAR, STRONG_BULL_CANDLE, STRONG_BEAR_CANDLE
}

/** Bias a pattern suggests IF its market context supports it. NEUTRAL patterns never lean. */
enum class PatternBias { BULLISH, BEARISH, NEUTRAL }

data class DetectedPattern(val pattern: CandlePattern, val bias: PatternBias, val detail: String)

/** Section 7: candle anatomy and pattern detection for the newest candle of a series. */
object PriceActionEngine {

    fun biasOf(p: CandlePattern): PatternBias = when (p) {
        CandlePattern.HAMMER, CandlePattern.BULLISH_ENGULFING, CandlePattern.PIN_BAR_BULL,
        CandlePattern.MORNING_STAR, CandlePattern.STRONG_BULL_CANDLE -> PatternBias.BULLISH
        CandlePattern.SHOOTING_STAR, CandlePattern.BEARISH_ENGULFING, CandlePattern.PIN_BAR_BEAR,
        CandlePattern.EVENING_STAR, CandlePattern.STRONG_BEAR_CANDLE -> PatternBias.BEARISH
        CandlePattern.DOJI, CandlePattern.INSIDE_BAR -> PatternBias.NEUTRAL
    }

    /** All patterns present on the newest candle, using only that candle and earlier ones. */
    fun detect(series: PriceSeries): List<DetectedPattern> {
        val i = series.size - 1
        if (i < 2) return emptyList()
        val c = series.candles[i]
        val p = series.candles[i - 1]
        val pp = series.candles[i - 2]
        val range = c.high - c.low
        if (range <= 0.0) return emptyList()
        val body = abs(c.close - c.open)
        val upper = c.high - maxOf(c.open, c.close)
        val lower = minOf(c.open, c.close) - c.low
        val atr = series.atr14[i]
        val relRange = if (atr.isNaN() || atr <= 0.0) 1.0 else range / atr

        val out = ArrayList<DetectedPattern>()
        fun add(pat: CandlePattern, detail: String) = out.add(DetectedPattern(pat, biasOf(pat), detail))

        if (body <= range * 0.1) add(CandlePattern.DOJI, "body ${pct(body / range)} of range")

        val smallBody = body <= range * 0.35
        if (smallBody && lower >= body * 2.0 && lower >= range * 0.55 && upper <= range * 0.15) {
            add(CandlePattern.HAMMER, "long lower wick, tiny upper wick")
        }
        if (smallBody && upper >= body * 2.0 && upper >= range * 0.55 && lower <= range * 0.15) {
            add(CandlePattern.SHOOTING_STAR, "long upper wick, tiny lower wick")
        }
        if (lower >= range * 0.66 && body <= range * 0.25) add(CandlePattern.PIN_BAR_BULL, "lower wick ${pct(lower / range)} of range")
        if (upper >= range * 0.66 && body <= range * 0.25) add(CandlePattern.PIN_BAR_BEAR, "upper wick ${pct(upper / range)} of range")

        val pBody = abs(p.close - p.open)
        if (c.close > c.open && p.close < p.open && c.open <= p.close && c.close >= p.open && body > pBody) {
            add(CandlePattern.BULLISH_ENGULFING, "green body engulfs previous red body")
        }
        if (c.close < c.open && p.close > p.open && c.open >= p.close && c.close <= p.open && body > pBody) {
            add(CandlePattern.BEARISH_ENGULFING, "red body engulfs previous green body")
        }
        if (c.high < p.high && c.low > p.low) add(CandlePattern.INSIDE_BAR, "range inside previous candle")

        val ppBody = abs(pp.close - pp.open)
        val ppMid = (pp.open + pp.close) / 2.0
        if (pp.close < pp.open && ppBody > 0.0 && pBody <= ppBody * 0.5 && c.close > c.open && c.close > ppMid) {
            add(CandlePattern.MORNING_STAR, "red, small, then green closing above first midpoint")
        }
        if (pp.close > pp.open && ppBody > 0.0 && pBody <= ppBody * 0.5 && c.close < c.open && c.close < ppMid) {
            add(CandlePattern.EVENING_STAR, "green, small, then red closing below first midpoint")
        }

        if (body >= range * 0.7 && relRange >= 1.0) {
            if (c.close > c.open) add(CandlePattern.STRONG_BULL_CANDLE, "body ${pct(body / range)} of range, range ${"%.1f".format(relRange)}x ATR")
            if (c.close < c.open) add(CandlePattern.STRONG_BEAR_CANDLE, "body ${pct(body / range)} of range, range ${"%.1f".format(relRange)}x ATR")
        }
        return out
    }

    private fun pct(x: Double): String = "${(x * 100).toInt()}%"
}
