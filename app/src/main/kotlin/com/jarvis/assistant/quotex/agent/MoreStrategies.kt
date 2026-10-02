package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.ConfluenceEngine
import com.jarvis.assistant.quotex.analysis.Indicators
import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.Strategy
import com.jarvis.assistant.quotex.analysis.StrategyCondition
import com.jarvis.assistant.quotex.analysis.StrategyResult
import com.jarvis.assistant.quotex.analysis.StructureLabel
import com.jarvis.assistant.quotex.analysis.Swing
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.quotex.analysis.defaultStrategies
import com.jarvis.assistant.trading.QuotexDecision
import kotlin.math.abs

private fun none(name: String) = StrategyResult(name, QuotexDecision.WAIT, emptyList())

private fun isUp(trend: TrendState) = trend == TrendState.STRONG_UP || trend == TrendState.WEAK_UP
private fun isDown(trend: TrendState) = trend == TrendState.STRONG_DOWN || trend == TrendState.WEAK_DOWN

/** Strategy 5: momentum indicators pointing the same way, with the newest candle agreeing. */
class MomentumConfirmationStrategy : Strategy {
    override val name = "Momentum Confirmation"
    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        if (i < 3) return none(name)
        val hist = series.macd.third
        val k = series.stochastic.first
        val rsi = series.rsi14[i]
        if (hist[i].isNaN() || hist[i - 1].isNaN() || k[i].isNaN() || k[i - 1].isNaN() || rsi.isNaN()) return none(name)
        val direction = when {
            hist[i] > 0.0 && rsi > 50.0 -> QuotexDecision.CALL
            hist[i] < 0.0 && rsi < 50.0 -> QuotexDecision.PUT
            else -> return none(name)
        }
        val up = direction == QuotexDecision.CALL
        val conditions = listOf(
            StrategyCondition("MACD histogram on the move's side", true, "hist=${hist[i]}"),
            StrategyCondition("MACD histogram strengthening", if (up) hist[i] > hist[i - 1] else hist[i] < hist[i - 1], "prev=${hist[i - 1]}"),
            StrategyCondition("Stochastic %K moving the same way", if (up) k[i] > k[i - 1] else k[i] < k[i - 1], "k=${k[i]}"),
            StrategyCondition("RSI not stretched", if (up) rsi in 50.0..72.0 else rsi in 28.0..50.0, "rsi=$rsi"),
            StrategyCondition("Latest candle agrees", if (up) series.up[i] else !series.up[i], "up=${series.up[i]}"),
            StrategyCondition("Volatility not extreme", volatility != VolatilityState.EXTREME, volatility.name)
        )
        return StrategyResult(name, direction, conditions)
    }
}

/** Strategy 6: ordering of EMA 9/21/50 (and 100/200 when enough history exists) plus price location. */
class EmaStructureStrategy : Strategy {
    override val name = "EMA Structure"
    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        if (i < 60) return none(name)
        val e9 = series.ema9[i]
        val e21 = series.ema21[i]
        val e50 = series.ema50[i]
        if (e9.isNaN() || e21.isNaN() || e50.isNaN()) return none(name)
        val bull = e9 > e21 && e21 > e50
        val bear = e9 < e21 && e21 < e50
        if (!bull && !bear) return none(name)
        val up = bull
        val direction = if (up) QuotexDecision.CALL else QuotexDecision.PUT
        val price = series.closes[i]
        val conditions = ArrayList<StrategyCondition>()
        conditions.add(StrategyCondition("EMA 9/21/50 stacked in order", true, "9=$e9 21=$e21 50=$e50"))
        conditions.add(StrategyCondition("Price on the correct side of EMA21", if (up) price > e21 else price < e21, "close=$price"))
        val slopeBars = 5
        conditions.add(
            StrategyCondition("EMA21 sloping the same way", if (up) e21 > series.ema21[i - slopeBars] else e21 < series.ema21[i - slopeBars], "slope over $slopeBars candles")
        )
        if (series.size >= 120) {
            val e100 = Indicators.ema(series.closes, 100)[i]
            if (!e100.isNaN()) conditions.add(StrategyCondition("EMA50 beyond EMA100", if (up) e50 > e100 else e50 < e100, "100=$e100"))
        }
        if (series.size >= 220) {
            val e200 = Indicators.ema(series.closes, 200)[i]
            if (!e200.isNaN()) conditions.add(StrategyCondition("Price beyond EMA200", if (up) price > e200 else price < e200, "200=$e200"))
        }
        conditions.add(StrategyCondition("Trend filter agrees", if (up) isUp(trend) else isDown(trend), trend.name))
        return StrategyResult(name, direction, conditions)
    }
}

/** Strategy 7: RSI and MACD must both turn the same way; neither is allowed to act alone. */
class RsiMacdConfirmationStrategy : Strategy {
    override val name = "RSI/MACD Confirmation"
    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        if (i < 5) return none(name)
        val rsi = series.rsi14
        val hist = series.macd.third
        if (rsi[i].isNaN() || rsi[i - 2].isNaN() || hist[i].isNaN() || hist[i - 1].isNaN()) return none(name)
        val rsiRising = rsi[i] > rsi[i - 2]
        val rsiFalling = rsi[i] < rsi[i - 2]
        val direction = when {
            rsiRising && hist[i] > hist[i - 1] && rsi[i] < 70.0 -> QuotexDecision.CALL
            rsiFalling && hist[i] < hist[i - 1] && rsi[i] > 30.0 -> QuotexDecision.PUT
            else -> return none(name)
        }
        val up = direction == QuotexDecision.CALL
        val conditions = listOf(
            StrategyCondition("RSI turning with the move", true, "rsi=${rsi[i]} two candles ago=${rsi[i - 2]}"),
            StrategyCondition("MACD histogram turning with the move", true, "hist=${hist[i]} prev=${hist[i - 1]}"),
            StrategyCondition("RSI recovered from an extreme", if (up) rsi[i - 2] < 50.0 else rsi[i - 2] > 50.0, "rsi[-2]=${rsi[i - 2]}"),
            StrategyCondition("MACD histogram crossed or is near zero", abs(hist[i]) <= abs(hist[i - 1]) * 1.5 || hist[i] * hist[i - 1] <= 0.0, "hist=${hist[i]}"),
            StrategyCondition("Not fighting a strong opposite trend", if (up) trend != TrendState.STRONG_DOWN else trend != TrendState.STRONG_UP, trend.name)
        )
        return StrategyResult(name, direction, conditions)
    }
}

/** Strategy 8: Bollinger Band behaviour read in the context of the trend/range regime. */
class BollingerRegimeStrategy : Strategy {
    override val name = "Bollinger Volatility Regime"
    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        if (i < 25) return none(name)
        val b = series.percentB[i]
        if (b.isNaN()) return none(name)
        val upper = series.bollinger.third
        val mid = series.bollinger.second
        val lower = series.bollinger.first
        val width = if (mid[i].isNaN() || mid[i] == 0.0 || upper[i].isNaN() || lower[i].isNaN()) Double.NaN else (upper[i] - lower[i]) / mid[i]
        val rsi = series.rsi14[i]
        return when {
            trend == TrendState.RANGE && b < 0.1 -> StrategyResult(
                name, QuotexDecision.CALL, listOf(
                    StrategyCondition("Range regime (mean reversion allowed)", true, trend.name),
                    StrategyCondition("Price at or below lower band", b < 0.1, "percentB=$b"),
                    StrategyCondition("Candle turning up", series.up[i], "up=${series.up[i]}"),
                    StrategyCondition("Not a volatility blow-out", volatility != VolatilityState.EXTREME && volatility != VolatilityState.HIGH, volatility.name),
                    StrategyCondition("RSI not in free fall", !rsi.isNaN() && rsi > 20.0, "rsi=$rsi")
                )
            )
            trend == TrendState.RANGE && b > 0.9 -> StrategyResult(
                name, QuotexDecision.PUT, listOf(
                    StrategyCondition("Range regime (mean reversion allowed)", true, trend.name),
                    StrategyCondition("Price at or above upper band", b > 0.9, "percentB=$b"),
                    StrategyCondition("Candle turning down", !series.up[i], "up=${series.up[i]}"),
                    StrategyCondition("Not a volatility blow-out", volatility != VolatilityState.EXTREME && volatility != VolatilityState.HIGH, volatility.name),
                    StrategyCondition("RSI not in a runaway rally", !rsi.isNaN() && rsi < 80.0, "rsi=$rsi")
                )
            )
            trend == TrendState.STRONG_UP && b in 0.6..1.1 -> StrategyResult(
                name, QuotexDecision.CALL, listOf(
                    StrategyCondition("Strong uptrend (band walking, no fading)", true, trend.name),
                    StrategyCondition("Price in upper half of the bands", b >= 0.6, "percentB=$b"),
                    StrategyCondition("Bands expanding or wide", !width.isNaN() && width > 0.0, "width=$width"),
                    StrategyCondition("Volatility not extreme", volatility != VolatilityState.EXTREME, volatility.name)
                )
            )
            trend == TrendState.STRONG_DOWN && b in -0.1..0.4 -> StrategyResult(
                name, QuotexDecision.PUT, listOf(
                    StrategyCondition("Strong downtrend (band walking, no fading)", true, trend.name),
                    StrategyCondition("Price in lower half of the bands", b <= 0.4, "percentB=$b"),
                    StrategyCondition("Bands expanding or wide", !width.isNaN() && width > 0.0, "width=$width"),
                    StrategyCondition("Volatility not extreme", volatility != VolatilityState.EXTREME, volatility.name)
                )
            )
            else -> none(name)
        }
    }
}

/** Strategy 9: a candle pattern only counts when the surrounding context supports it (section 7). */
class PriceActionConfirmationStrategy : Strategy {
    override val name = "Price Action Confirmation"
    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val i = series.size - 1
        val atr = if (i >= 0) series.atr14[i] else Double.NaN
        if (i < 5 || atr.isNaN() || atr <= 0.0) return none(name)
        val patterns = PriceActionEngine.detect(series).filter { it.bias != PatternBias.NEUTRAL }
        if (patterns.isEmpty()) return none(name)
        val bulls = patterns.count { it.bias == PatternBias.BULLISH }
        val bears = patterns.count { it.bias == PatternBias.BEARISH }
        if (bulls == bears) return none(name)
        val up = bulls > bears
        val price = series.closes[i]
        val support = swings.filter { !it.isHigh && it.index < i }.minByOrNull { abs(it.price - price) }
        val resistance = swings.filter { it.isHigh && it.index < i }.minByOrNull { abs(it.price - price) }
        val atLevel = if (up) support != null && abs(support.price - price) <= atr else resistance != null && abs(resistance.price - price) <= atr
        val intoOpposite = if (up) resistance != null && resistance.price > price && resistance.price - price < atr * 0.5
        else support != null && support.price < price && price - support.price < atr * 0.5
        val named = patterns.first { (it.bias == PatternBias.BULLISH) == up }
        val conditions = listOf(
            StrategyCondition("Pattern present: ${named.pattern.name}", true, named.detail),
            StrategyCondition("Pattern is at a relevant support/resistance level", atLevel, "within 1 ATR of a swing level"),
            StrategyCondition("Trend context does not oppose it", if (up) !isDown(trend) || atLevel else !isUp(trend) || atLevel, trend.name),
            StrategyCondition("Not entering straight into opposing level", !intoOpposite, "opposing level within half an ATR"),
            StrategyCondition("Volatility not extreme", volatility != VolatilityState.EXTREME, volatility.name)
        )
        return StrategyResult(name, if (up) QuotexDecision.CALL else QuotexDecision.PUT, conditions)
    }
}

/**
 * Strategy 10: the base timeframe must agree with two resampled higher timeframes ([middleFactor] for
 * momentum, [higherFactor] for context). Disagreement means WAIT, never a forced direction.
 */
class MultiTimeframeConfluenceStrategy(
    private val middleFactor: Int = 4,
    private val higherFactor: Int = 12
) : Strategy {
    override val name = "Multi-Timeframe Confluence"
    override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
        val baseMs = CandleResampler.inferCandleMs(series.candles) ?: return none(name)
        val mid = PriceSeries(CandleResampler.resample(series.candles, middleFactor, baseMs))
        val high = PriceSeries(CandleResampler.resample(series.candles, higherFactor, baseMs))
        if (mid.size < 30 || high.size < 30) return none(name)
        val midTrend = MarketStructure.trendLabel(mid)
        val highTrend = MarketStructure.trendLabel(high)
        val highStructure = MarketStructure.structureLabel(MarketStructure.swings(high))
        val dir = when {
            isUp(highTrend) && isUp(midTrend) -> QuotexDecision.CALL
            isDown(highTrend) && isDown(midTrend) -> QuotexDecision.PUT
            else -> return none(name)
        }
        val up = dir == QuotexDecision.CALL
        val conditions = listOf(
            StrategyCondition("Higher timeframe trend", true, highTrend.name),
            StrategyCondition("Middle timeframe trend agrees", true, midTrend.name),
            StrategyCondition("Higher timeframe structure supports", if (up) highStructure == StructureLabel.HIGHER_HIGH || highStructure == StructureLabel.HIGHER_LOW else highStructure == StructureLabel.LOWER_HIGH || highStructure == StructureLabel.LOWER_LOW, highStructure.name),
            StrategyCondition("Base timeframe trend agrees", if (up) isUp(trend) else isDown(trend), trend.name),
            StrategyCondition("Base candle confirms", if (up) series.up[series.size - 1] else !series.up[series.size - 1], "latest candle"),
            StrategyCondition("Volatility not extreme", volatility != VolatilityState.EXTREME, volatility.name)
        )
        return StrategyResult(name, dir, conditions)
    }
}

/** All ten strategies from the spec. The original four stay first so existing behaviour is unchanged. */
fun fullStrategyLibrary(): List<Strategy> = defaultStrategies() + listOf(
    MomentumConfirmationStrategy(), EmaStructureStrategy(), RsiMacdConfirmationStrategy(),
    BollingerRegimeStrategy(), PriceActionConfirmationStrategy(), MultiTimeframeConfluenceStrategy()
)

fun fullConfluenceEngine(weights: Map<String, Double> = emptyMap()): ConfluenceEngine =
    ConfluenceEngine(fullStrategyLibrary(), weights)
