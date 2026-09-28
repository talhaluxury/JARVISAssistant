package com.jarvis.assistant.quotex.analysis

/** A confirmed swing point: a local high or low that stood out from [lookback] candles on each side. */
data class Swing(val index: Int, val price: Double, val isHigh: Boolean)

/** Higher-high/higher-low structure read from the most recent two swings of each kind. */
enum class StructureLabel { HIGHER_HIGH, HIGHER_LOW, LOWER_HIGH, LOWER_LOW, UNCLEAR }

enum class TrendState { STRONG_UP, WEAK_UP, RANGE, WEAK_DOWN, STRONG_DOWN, UNSTABLE }

enum class VolatilityState { LOW, NORMAL, HIGH, EXTREME, UNKNOWN }

/**
 * Descriptive market-structure reads: swing highs/lows, HH/HL/LH/LL, a trend label (from ADX + EMA
 * ordering) and a volatility label (from ATR relative to its own recent range). These describe the
 * market; they do not by themselves generate a trade - the confluence still comes from the ensemble.
 */
object MarketStructure {

    /** Confirmed swing points: index i is a swing high/low only once [lookback] later candles are known. */
    fun swings(series: PriceSeries, lookback: Int = 3): List<Swing> {
        val n = series.size
        if (n < lookback * 2 + 1) return emptyList()
        val out = ArrayList<Swing>()
        for (i in lookback until n - lookback) {
            val high = series.highs[i]
            val low = series.lows[i]
            var isHigh = true
            var isLow = true
            for (j in (i - lookback)..(i + lookback)) {
                if (j == i) continue
                if (series.highs[j] >= high) isHigh = false
                if (series.lows[j] <= low) isLow = false
            }
            if (isHigh) out.add(Swing(i, high, true))
            if (isLow) out.add(Swing(i, low, false))
        }
        return out
    }

    /** Compares the two most recent confirmed highs and the two most recent confirmed lows. */
    fun structureLabel(swingList: List<Swing>): StructureLabel {
        val highs = swingList.filter { it.isHigh }.takeLast(2)
        val lows = swingList.filter { !it.isHigh }.takeLast(2)
        if (highs.size == 2 && highs[1].price > highs[0].price) return StructureLabel.HIGHER_HIGH
        if (highs.size == 2 && highs[1].price < highs[0].price) return StructureLabel.LOWER_HIGH
        if (lows.size == 2 && lows[1].price > lows[0].price) return StructureLabel.HIGHER_LOW
        if (lows.size == 2 && lows[1].price < lows[0].price) return StructureLabel.LOWER_LOW
        return StructureLabel.UNCLEAR
    }

    /** ADX for strength, EMA9/EMA21/EMA50 ordering for direction. Requires enough history for both. */
    fun trendLabel(series: PriceSeries): TrendState {
        val i = series.size - 1
        if (i < 0) return TrendState.UNSTABLE
        val adx = series.adx14[i]
        val fast = series.ema9[i]
        val mid = series.ema21[i]
        val slow = series.ema50[i]
        if (adx.isNaN()) return TrendState.UNSTABLE
        val bullish = fast > mid && mid > slow
        val bearish = fast < mid && mid < slow
        return when {
            adx < 18 -> TrendState.RANGE
            adx < 30 -> if (bullish) TrendState.WEAK_UP else if (bearish) TrendState.WEAK_DOWN else TrendState.RANGE
            bullish -> TrendState.STRONG_UP
            bearish -> TrendState.STRONG_DOWN
            fast > mid -> TrendState.WEAK_UP
            else -> TrendState.WEAK_DOWN
        }
    }

    /** ATR now vs. the median ATR of the last [lookback] candles - relative, not an arbitrary constant. */
    fun volatilityLabel(series: PriceSeries, lookback: Int = 100): VolatilityState {
        val i = series.size - 1
        if (i < 0 || series.atr14[i].isNaN()) return VolatilityState.UNKNOWN
        val start = maxOf(0, i - lookback + 1)
        val recent = (start..i).map { series.atr14[it] }.filter { !it.isNaN() }
        if (recent.size < 20) return VolatilityState.UNKNOWN
        val sorted = recent.sorted()
        val median = sorted[sorted.size / 2]
        if (median <= 0.0) return VolatilityState.UNKNOWN
        val ratio = series.atr14[i] / median
        return when {
            ratio < 0.6 -> VolatilityState.LOW
            ratio < 1.5 -> VolatilityState.NORMAL
            ratio < 2.5 -> VolatilityState.HIGH
            else -> VolatilityState.EXTREME
        }
    }
}
