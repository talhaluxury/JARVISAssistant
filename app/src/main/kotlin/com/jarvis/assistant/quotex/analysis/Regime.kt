package com.jarvis.assistant.quotex.analysis

/** Section 10. Strategies are only enabled in regimes they were designed for. */
enum class MarketRegime {
    STRONG_UPTREND, WEAK_UPTREND, STRONG_DOWNTREND, WEAK_DOWNTREND,
    RANGE, BREAKOUT, BREAKDOWN, TRANSITION, UNSTABLE
}

object RegimeClassifier {

    fun classify(series: PriceSeries, trend: TrendState, volatility: VolatilityState, swings: List<Swing>): MarketRegime {
        val i = series.size - 1
        if (i < 25) return MarketRegime.UNSTABLE
        if (volatility == VolatilityState.EXTREME || volatility == VolatilityState.UNKNOWN || trend == TrendState.UNSTABLE) {
            return MarketRegime.UNSTABLE
        }

        // Breakout / breakdown: a close beyond a previously tight 20-candle range.
        val atr = series.atr14[i]
        if (!atr.isNaN() && atr > 0.0) {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (j in (i - 20) until i) {
                if (series.highs[j] > hi) hi = series.highs[j]
                if (series.lows[j] < lo) lo = series.lows[j]
            }
            val tight = (hi - lo) <= 6.0 * atr
            if (tight && series.closes[i] > hi) return MarketRegime.BREAKOUT
            if (tight && series.closes[i] < lo) return MarketRegime.BREAKDOWN
        }

        // Transition: the EMA/ADX trend and the swing structure disagree.
        val structure = MarketStructure.structureLabel(swings)
        val upTrend = trend == TrendState.STRONG_UP || trend == TrendState.WEAK_UP
        val downTrend = trend == TrendState.STRONG_DOWN || trend == TrendState.WEAK_DOWN
        if (upTrend && (structure == StructureLabel.LOWER_HIGH || structure == StructureLabel.LOWER_LOW)) return MarketRegime.TRANSITION
        if (downTrend && (structure == StructureLabel.HIGHER_HIGH || structure == StructureLabel.HIGHER_LOW)) return MarketRegime.TRANSITION

        return when (trend) {
            TrendState.STRONG_UP -> MarketRegime.STRONG_UPTREND
            TrendState.WEAK_UP -> MarketRegime.WEAK_UPTREND
            TrendState.STRONG_DOWN -> MarketRegime.STRONG_DOWNTREND
            TrendState.WEAK_DOWN -> MarketRegime.WEAK_DOWNTREND
            TrendState.RANGE -> MarketRegime.RANGE
            TrendState.UNSTABLE -> MarketRegime.UNSTABLE
        }
    }
}
