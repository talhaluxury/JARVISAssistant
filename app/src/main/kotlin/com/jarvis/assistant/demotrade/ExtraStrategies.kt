package com.jarvis.assistant.demotrade

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun xVote(s: Strategy, dir: Dir, score: Double, reasons: List<String>): StrategyVote =
    StrategyVote(s.name, dir, score.coerceIn(0.0, 100.0).toInt(), reasons)

private fun xWait(s: Strategy, why: String): StrategyVote = StrategyVote(s.name, Dir.WAIT, 0, listOf(why))

private fun xf(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/**
 * H: Bollinger squeeze breakout. Bands that were unusually tight on the previous candle, then a strong candle that CLOSES
 * outside those previous bands: volatility expansion with direction. Uses only candles up to the one that just closed.
 */
object BollingerSqueezeBreakout : Strategy {
    override val id = "squeeze"
    override val name = "Squeeze Breakout"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val n = ind.n
        val atr = ind.atrNow
        if (n < 70 || atr.isNaN() || atr <= 0.0) return xWait(this, "not enough candles")
        if (ctx.regime.regime == Regime.HIGH_VOLATILITY) return xWait(this, "volatility already extreme")
        fun width(i: Int): Double {
            val u = ind.bb.upper[i]; val l = ind.bb.lower[i]; val m = ind.bb.mid[i]
            return if (u.isNaN() || l.isNaN() || m.isNaN() || m <= 0.0) Double.NaN else (u - l) / m
        }
        val prev = n - 2
        val prevWidth = width(prev)
        if (prevWidth.isNaN() || prevWidth <= 0.0) return xWait(this, "bands not ready")
        var sum = 0.0
        var cnt = 0
        for (i in max(0, prev - 50) until prev) {
            val w = width(i)
            if (!w.isNaN()) { sum += w; cnt++ }
        }
        if (cnt < 30) return xWait(this, "not enough band history")
        val avgWidth = sum / cnt
        val compression = avgWidth / prevWidth
        if (compression < 1.3) return xWait(this, "no squeeze before this candle (compression ${xf(compression)}x)")
        val c = ind.candles[n - 1]
        val range = c.high - c.low
        val body = abs(c.close - c.open)
        if (range <= 0.0 || body < 0.5 * range) return xWait(this, "breakout candle has a weak body")
        val up = c.close > ind.bb.upper[prev] && c.close > c.open
        val down = c.close < ind.bb.lower[prev] && c.close < c.open
        if (!up && !down) return xWait(this, "squeeze present, no close outside the bands yet")
        val dir = if (up) Dir.CALL else Dir.PUT
        var score = 58.0 + min(14.0, (compression - 1.0) * 20.0)
        val reasons = ArrayList<String>()
        reasons.add("Bollinger squeeze ${xf(compression)}x tighter than normal")
        reasons.add("closed ${if (up) "above" else "below"} the previous band")
        if (body >= 0.7 * range) { score += 6.0; reasons.add("full-bodied breakout candle") }
        val adx = ind.at(ind.adx14.adx)
        val adxPrev = ind.at(ind.adx14.adx, 2)
        if (!adx.isNaN() && !adxPrev.isNaN() && adx > adxPrev) { score += 4.0; reasons.add("ADX rising") }
        if (ctx.mtf.available && ctx.mtf.higher == dir) { score += 5.0; reasons.add("higher timeframe agrees") }
        return xVote(this, dir, score, reasons)
    }
}

/**
 * I: VWAP (tick-activity weighted, see TA.vwap). In a trend: buy the first pullback that holds VWAP / sell the first rally that
 * fails at it. In a range: fade a stretch of more than 1.6 ATR away from VWAP once the candle turns back.
 */
object VwapStrategy : Strategy {
    override val id = "vwap"
    override val name = "VWAP"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val atr = ind.atrNow
        val vwap = ind.at(ind.vwap)
        if (ind.n < 70 || atr.isNaN() || atr <= 0.0 || vwap.isNaN()) return xWait(this, "VWAP not ready")
        val c = ind.candles[ind.n - 1]
        val price = c.close
        val dist = (price - vwap) / atr
        val kind = if (ind.hasActivityData) "tick-weighted VWAP" else "VWAP (equal weights, no tick data)"
        when (ctx.regime.regime) {
            Regime.TREND_UP -> {
                if (price > vwap && abs(dist) <= 0.6 && c.close > c.open && ind.low[ind.n - 1] <= vwap + 0.3 * atr) {
                    return xVote(this, Dir.CALL, 62.0 + min(8.0, (0.6 - abs(dist)) * 12.0), listOf("uptrend pullback held $kind", "bullish close off VWAP"))
                }
            }
            Regime.TREND_DOWN -> {
                if (price < vwap && abs(dist) <= 0.6 && c.close < c.open && ind.high[ind.n - 1] >= vwap - 0.3 * atr) {
                    return xVote(this, Dir.PUT, 62.0 + min(8.0, (0.6 - abs(dist)) * 12.0), listOf("downtrend rally failed at $kind", "bearish close off VWAP"))
                }
            }
            Regime.RANGE -> {
                if (dist >= 1.6 && c.close < c.open) {
                    return xVote(this, Dir.PUT, 56.0 + min(10.0, (dist - 1.6) * 8.0), listOf("range: price ${xf(dist)} ATR above $kind", "candle turned down"))
                }
                if (dist <= -1.6 && c.close > c.open) {
                    return xVote(this, Dir.CALL, 56.0 + min(10.0, (-dist - 1.6) * 8.0), listOf("range: price ${xf(-dist)} ATR below $kind", "candle turned up"))
                }
            }
            else -> Unit
        }
        return xWait(this, "no VWAP setup in regime ${ctx.regime.regime} (price ${xf(dist)} ATR from VWAP)")
    }
}

/**
 * J: Stochastic reversal at the Bollinger band, RANGE markets only: %K turns up from oversold at the lower band (or down from
 * overbought at the upper band) with RSI confirming the stretch. Mean reversion is never attempted in a trend.
 */
object StochasticReversal : Strategy {
    override val id = "stoch_reversal"
    override val name = "Stochastic Reversal"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val atr = ind.atrNow
        val k = ind.at(ind.stoch.k)
        val kPrev = ind.at(ind.stoch.k, 1)
        val d = ind.at(ind.stoch.d)
        val rsi = ind.at(ind.rsi14)
        val upper = ind.at(ind.bb.upper)
        val lower = ind.at(ind.bb.lower)
        if (ind.n < 60 || atr.isNaN() || atr <= 0.0 || k.isNaN() || kPrev.isNaN() || d.isNaN() || rsi.isNaN() || upper.isNaN() || lower.isNaN()) {
            return xWait(this, "indicators not ready")
        }
        if (ctx.regime.regime != Regime.RANGE) return xWait(this, "mean reversion only in a range (regime ${ctx.regime.regime})")
        val c = ind.candles[ind.n - 1]
        val price = c.close
        val bull = kPrev < 20.0 && k > kPrev && k > d && price <= lower + 0.25 * atr && rsi < 40.0
        val bear = kPrev > 80.0 && k < kPrev && k < d && price >= upper - 0.25 * atr && rsi > 60.0
        if (!bull && !bear) return xWait(this, "no stochastic turn at a band (%K ${xf(k)}, RSI ${xf(rsi)})")
        val dir = if (bull) Dir.CALL else Dir.PUT
        var score = 58.0 + min(12.0, max(0.0, abs(rsi - 50.0) - 10.0) * 0.8)
        val reasons = arrayListOf("stochastic %K turned ${if (bull) "up from oversold" else "down from overbought"} (${xf(k)})", "price at the ${if (bull) "lower" else "upper"} Bollinger band")
        if ((bull && c.close > c.open) || (bear && c.close < c.open)) { score += 6.0; reasons.add("candle confirms the turn") }
        return xVote(this, dir, score, reasons)
    }
}
