package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState

/** Section 10: the market regime the strategies are allowed to operate in. */
enum class MarketRegime {
    STRONG_UPTREND, WEAK_UPTREND, STRONG_DOWNTREND, WEAK_DOWNTREND, RANGE, BREAKOUT, BREAKDOWN, TRANSITION, UNSTABLE
}

object RegimeClassifier {

    fun classify(series: PriceSeries): MarketRegime {
        val i = series.size - 1
        if (i < 50) return MarketRegime.UNSTABLE
        val trend = MarketStructure.trendLabel(series)
        val vol = MarketStructure.volatilityLabel(series)
        if (trend == TrendState.UNSTABLE || vol == VolatilityState.UNKNOWN || vol == VolatilityState.EXTREME) return MarketRegime.UNSTABLE

        val swings = MarketStructure.swings(series)
        val atr = series.atr14[i]
        val price = series.closes[i]
        val lastHigh = swings.lastOrNull { it.isHigh && it.index < i - 3 }
        val lastLow = swings.lastOrNull { !it.isHigh && it.index < i - 3 }
        val adxNow = series.adx14[i]
        val adxBefore = if (i >= 5) series.adx14[i - 5] else Double.NaN
        val adxRising = !adxNow.isNaN() && !adxBefore.isNaN() && adxNow > adxBefore

        // A breakout needs a close beyond a confirmed swing AND rising trend strength - one wick is not enough.
        if (lastHigh != null && !atr.isNaN() && price > lastHigh.price + atr * 0.25 && adxRising) return MarketRegime.BREAKOUT
        if (lastLow != null && !atr.isNaN() && price < lastLow.price - atr * 0.25 && adxRising) return MarketRegime.BREAKDOWN

        return when (trend) {
            TrendState.STRONG_UP -> MarketRegime.STRONG_UPTREND
            TrendState.WEAK_UP -> MarketRegime.WEAK_UPTREND
            TrendState.STRONG_DOWN -> MarketRegime.STRONG_DOWNTREND
            TrendState.WEAK_DOWN -> MarketRegime.WEAK_DOWNTREND
            TrendState.RANGE -> if (!adxNow.isNaN() && adxNow in 18.0..25.0 && adxRising) MarketRegime.TRANSITION else MarketRegime.RANGE
            TrendState.UNSTABLE -> MarketRegime.UNSTABLE
        }
    }
}

/** Which strategy families may run in which regime. Trend-following never blindly in a range, reversal never blindly in a strong trend. */
object RegimeGate {
    private val trendFollowing = setOf("Trend Continuation", "Pullback", "EMA Structure", "Momentum Confirmation", "Multi-Timeframe Confluence")
    private val reversal = setOf("Support/Resistance Reversal", "RSI/MACD Confirmation")

    fun allows(strategyName: String, regime: MarketRegime): Boolean = when (regime) {
        MarketRegime.UNSTABLE -> false
        MarketRegime.STRONG_UPTREND, MarketRegime.STRONG_DOWNTREND -> strategyName !in reversal
        MarketRegime.WEAK_UPTREND, MarketRegime.WEAK_DOWNTREND -> true
        MarketRegime.RANGE -> strategyName !in trendFollowing
        MarketRegime.BREAKOUT, MarketRegime.BREAKDOWN -> strategyName !in reversal
        MarketRegime.TRANSITION -> strategyName in reversal || strategyName == "Price Action Confirmation" || strategyName == "Bollinger Volatility Regime"
    }
}

/** Section 23: trading session by UTC hour. Boundaries are conventional approximations, not exchange hours. */
enum class MarketSession { ASIAN, LONDON, LONDON_NY_OVERLAP, NEW_YORK, OFF_HOURS }

object SessionClassifier {
    fun at(epochMs: Long): MarketSession {
        val hour = (Math.floorMod(epochMs / 3_600_000L, 24L)).toInt()
        return when (hour) {
            in 0..6 -> MarketSession.ASIAN
            in 7..11 -> MarketSession.LONDON
            in 12..15 -> MarketSession.LONDON_NY_OVERLAP
            in 16..20 -> MarketSession.NEW_YORK
            else -> MarketSession.OFF_HOURS
        }
    }
}
