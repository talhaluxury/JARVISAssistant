package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.trading.QuotexDecision
import kotlin.math.abs

/** One condition a strategy checked, and whether the current market satisfied it. */
data class StrategyCondition(val name: String, val satisfied: Boolean, val detail: String)

data class StrategyResult(
    val strategyName: String,
    /** WAIT means this strategy sees no setup at all right now (not enough of its conditions apply). */
    val direction: QuotexDecision,
    val conditions: List<StrategyCondition>
) {
    val satisfiedCount: Int get() = conditions.count { it.satisfied }
    val totalCount: Int get() = conditions.size
    val score: Double get() = if (totalCount == 0) 0.0 else satisfiedCount.toDouble() / totalCount
}

interface Strategy {
    val name: String
    fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult
}

private fun result(name: String, direction: QuotexDecision, conditions: List<StrategyCondition>) =
    StrategyResult(name, direction, conditions)

/** Trades WITH an established trend: price on the trend side of EMA21, MACD agreeing, momentum not exhausted. */
class TrendContinuationStrategy : Strategy {
    override val name = "Trend Continuation"

    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        val direction = when (trend) {
            TrendState.STRONG_UP, TrendState.WEAK_UP -> QuotexDecision.CALL
            TrendState.STRONG_DOWN, TrendState.WEAK_DOWN -> QuotexDecision.PUT
            else -> QuotexDecision.WAIT
        }
        if (direction == QuotexDecision.WAIT || i < 0) return result(name, QuotexDecision.WAIT, emptyList())
        val up = direction == QuotexDecision.CALL
        val ema21 = series.ema21[i]
        val histogram = series.macd.third[i]
        val rsi = series.rsi14[i]
        val conditions = listOf(
            StrategyCondition("Trend established", trend == TrendState.STRONG_UP || trend == TrendState.STRONG_DOWN, trend.name),
            StrategyCondition("Price on trend side of EMA21", if (up) series.closes[i] > ema21 else series.closes[i] < ema21, "close=${series.closes[i]} ema21=$ema21"),
            StrategyCondition("MACD histogram agrees", !histogram.isNaN() && (if (up) histogram > 0 else histogram < 0), "histogram=$histogram"),
            StrategyCondition("Momentum not exhausted", !rsi.isNaN() && (if (up) rsi in 40.0..75.0 else rsi in 25.0..60.0), "rsi=$rsi")
        )
        return result(name, direction, conditions)
    }
}

/** Trades a shallow pullback INTO a strong trend, once price shows signs of resuming it. */
class PullbackStrategy : Strategy {
    override val name = "Pullback"

    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        val direction = when (trend) {
            TrendState.STRONG_UP -> QuotexDecision.CALL
            TrendState.STRONG_DOWN -> QuotexDecision.PUT
            else -> QuotexDecision.WAIT
        }
        if (direction == QuotexDecision.WAIT || i < 1) return result(name, QuotexDecision.WAIT, emptyList())
        val up = direction == QuotexDecision.CALL
        val atr = series.atr14[i]
        val ema21 = series.ema21[i]
        val rsi = series.rsi14[i]
        val nearEma = !atr.isNaN() && atr > 0.0 && abs(series.closes[i] - ema21) <= atr
        val conditions = listOf(
            StrategyCondition("Strong trend to trade with", true, trend.name),
            StrategyCondition("Price has pulled back near EMA21", nearEma, "distance=${abs(series.closes[i] - ema21)} atr=$atr"),
            StrategyCondition("RSI shows a pullback, not a reversal", !rsi.isNaN() && rsi in 35.0..65.0, "rsi=$rsi"),
            StrategyCondition("Latest candle resumed the trend", if (up) series.up[i] else !series.up[i], "up=${series.up[i]}")
        )
        return result(name, direction, conditions)
    }
}

/** Trades a break of a recent swing level, followed by a retest that holds. */
class BreakoutRetestStrategy : Strategy {
    override val name = "Breakout + Retest"

    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        if (i < 3) return result(name, QuotexDecision.WAIT, emptyList())
        val atr = series.atr14[i]
        val tolerance = if (atr.isNaN() || atr <= 0.0) return result(name, QuotexDecision.WAIT, emptyList()) else atr

        val lastHigh = swings.lastOrNull { it.isHigh && it.index < i }
        val lastLow = swings.lastOrNull { !it.isHigh && it.index < i }
        val recentLow = (maxOf(0, i - 2)..i).minOf { series.lows[it] }
        val recentHigh = (maxOf(0, i - 2)..i).maxOf { series.highs[it] }

        val brokeUp = lastHigh != null && series.closes[i] > lastHigh.price
        val retestedUp = brokeUp && recentLow <= lastHigh!!.price + tolerance * 0.5
        val heldUp = brokeUp && series.closes[i] > lastHigh!!.price

        val brokeDown = lastLow != null && series.closes[i] < lastLow.price
        val retestedDown = brokeDown && recentHigh >= lastLow!!.price - tolerance * 0.5
        val heldDown = brokeDown && series.closes[i] < lastLow!!.price

        return when {
            brokeUp -> result(
                name, QuotexDecision.CALL, listOf(
                    StrategyCondition("Closed above the recent swing high", brokeUp, "level=${lastHigh?.price}"),
                    StrategyCondition("Price retested the broken level", retestedUp, "recentLow=$recentLow"),
                    StrategyCondition("Price held above after the retest", heldUp, "close=${series.closes[i]}"),
                    StrategyCondition("Volatility allows a clean read", volatility != VolatilityState.EXTREME, volatility.name)
                )
            )
            brokeDown -> result(
                name, QuotexDecision.PUT, listOf(
                    StrategyCondition("Closed below the recent swing low", brokeDown, "level=${lastLow?.price}"),
                    StrategyCondition("Price retested the broken level", retestedDown, "recentHigh=$recentHigh"),
                    StrategyCondition("Price held below after the retest", heldDown, "close=${series.closes[i]}"),
                    StrategyCondition("Volatility allows a clean read", volatility != VolatilityState.EXTREME, volatility.name)
                )
            )
            else -> result(name, QuotexDecision.WAIT, emptyList())
        }
    }
}

/** Trades a rejection at a nearby swing level (support/resistance), confirmed by an extreme RSI reading. */
class SupportResistanceReversalStrategy : Strategy {
    override val name = "Support/Resistance Reversal"

    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        val atr = series.atr14[i]
        if (i < 0 || atr.isNaN() || atr <= 0.0) return result(name, QuotexDecision.WAIT, emptyList())
        val price = series.closes[i]
        val resistance = swings.filter { it.isHigh && it.index < i }.minByOrNull { abs(it.price - price) }
        val support = swings.filter { !it.isHigh && it.index < i }.minByOrNull { abs(it.price - price) }
        val rsi = series.rsi14[i]

        val body = abs(series.candles[i].close - series.candles[i].open)
        val upperWick = series.highs[i] - maxOf(series.candles[i].open, series.candles[i].close)
        val lowerWick = minOf(series.candles[i].open, series.candles[i].close) - series.lows[i]
        val rejectionDown = upperWick > body * 2.0
        val rejectionUp = lowerWick > body * 2.0

        val nearResistance = resistance != null && abs(resistance.price - price) <= atr * 0.5
        val nearSupport = support != null && abs(support.price - price) <= atr * 0.5

        return when {
            nearResistance -> result(
                name, QuotexDecision.PUT, listOf(
                    StrategyCondition("Price is near a resistance level", true, "level=${resistance?.price}"),
                    StrategyCondition("Rejection candle (long upper wick)", rejectionDown, "upperWick=$upperWick body=$body"),
                    StrategyCondition("RSI is overbought", !rsi.isNaN() && rsi > 65.0, "rsi=$rsi"),
                    StrategyCondition("Volatility is not extreme", volatility != VolatilityState.EXTREME, volatility.name)
                )
            )
            nearSupport -> result(
                name, QuotexDecision.CALL, listOf(
                    StrategyCondition("Price is near a support level", true, "level=${support?.price}"),
                    StrategyCondition("Rejection candle (long lower wick)", rejectionUp, "lowerWick=$lowerWick body=$body"),
                    StrategyCondition("RSI is oversold", !rsi.isNaN() && rsi < 35.0, "rsi=$rsi"),
                    StrategyCondition("Volatility is not extreme", volatility != VolatilityState.EXTREME, volatility.name)
                )
            )
            else -> result(name, QuotexDecision.WAIT, emptyList())
        }
    }
}

fun defaultStrategies(): List<Strategy> = listOf(
    TrendContinuationStrategy(), PullbackStrategy(), BreakoutRetestStrategy(), SupportResistanceReversalStrategy()
)
