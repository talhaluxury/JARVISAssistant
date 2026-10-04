package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/** Result of validating one market-data window. [unavailable] = "MARKET DATA UNAVAILABLE", otherwise just "not enough history yet". */
data class DataCheck(val ok: Boolean, val unavailable: Boolean, val reason: String?) {
    companion object {
        val OK = DataCheck(true, false, null)
    }
}

object DataValidator {
    private const val GAP_WINDOW = 60
    private const val MAX_JUMP_FRACTION = 0.02
    private const val MAX_STALE_CANDLES = 3
    private const val CHECK_WINDOW = 250

    fun validate(
        candles: List<Candle>,
        candleMs: Long,
        nowMs: Long,
        minCandles: Int,
        checkFresh: Boolean = true
    ): DataCheck {
        if (candleMs <= 0L) return DataCheck(false, true, "candle length unknown")
        if (candles.isEmpty()) return DataCheck(false, true, "no candles")
        val from = max(0, candles.size - CHECK_WINDOW)
        for (i in from until candles.size) {
            val c = candles[i]
            val vals = doubleArrayOf(c.open, c.high, c.low, c.close)
            if (vals.any { it.isNaN() || it.isInfinite() }) return DataCheck(false, true, "null/NaN price in candle ${i + 1}")
            if (vals.any { it <= 0.0 }) return DataCheck(false, true, "impossible (non-positive) price")
            if (c.high < c.low || c.high < max(c.open, c.close) || c.low > min(c.open, c.close)) {
                return DataCheck(false, true, "impossible candle (high/low inconsistent)")
            }
            if (i > from) {
                val p = candles[i - 1]
                if (c.openTimeMs == p.openTimeMs) return DataCheck(false, true, "duplicate timestamp")
                if (c.openTimeMs < p.openTimeMs) return DataCheck(false, true, "timestamps out of order")
                if (abs(c.close - p.close) / p.close > MAX_JUMP_FRACTION) return DataCheck(false, true, "large invalid price jump")
            }
            if (c.openTimeMs > nowMs) return DataCheck(false, true, "candle timestamp is in the future")
        }
        val gapFrom = max(from + 1, candles.size - GAP_WINDOW)
        for (i in gapFrom until candles.size) {
            if (candles[i].openTimeMs - candles[i - 1].openTimeMs > candleMs * 3 / 2) {
                return DataCheck(false, true, "missing candles in the last $GAP_WINDOW")
            }
        }
        if (checkFresh) {
            val lastClose = candles.last().openTimeMs + candleMs
            if (nowMs - lastClose > MAX_STALE_CANDLES * candleMs) return DataCheck(false, true, "data is stale")
        }
        if (candles.size < minCandles) return DataCheck(false, false, "collecting history (${candles.size}/$minCandles candles)")
        return DataCheck.OK
    }
}

data class ScoreResult(
    val direction: Dir,
    val score: Int,
    val components: List<ScoreComponent>
)

/**
 * Transparent weighted score. Each category yields an alignment in -1..1 toward the candidate direction; the weights add up
 * to 100, so  score = 50 + sum(weight x alignment) / 2.  Neutral input = 50 (WAIT); everything aligned = 100.
 */
object SignalScorer {
    const val W_TREND = 16.0
    const val W_MOMENTUM = 9.0
    const val W_RSI = 8.0
    const val W_MACD = 10.0
    const val W_EMA = 9.0
    const val W_VWAP = 4.0
    const val W_SR = 10.0
    const val W_CANDLE = 8.0
    const val W_VOLATILITY = 6.0
    const val W_SETUP = 6.0
    const val W_MTF = 8.0
    const val W_REGIME = 6.0

    private class Raw(val name: String, val weight: Double, val bull: Double, val note: String)

    fun score(ctx: AnalysisContext, votes: List<StrategyVote>): ScoreResult {
        val ind = ctx.ind
        val atr = ind.atrNow
        if (atr.isNaN() || atr <= 0.0) return ScoreResult(Dir.WAIT, 50, emptyList())
        val price = ind.price
        val directional = ArrayList<Raw>()

        // Trend confirmation: EMA stack scaled by ADX strength
        val e9 = ind.at(ind.ema9); val e21 = ind.at(ind.ema21); val e50 = ind.at(ind.ema50); val e200 = ind.at(ind.ema200)
        var up = 0; var down = 0; var seen = 0
        fun stack(a: Double, b: Double) { if (!a.isNaN() && !b.isNaN()) { seen++; if (a > b) up++ else if (a < b) down++ } }
        stack(e9, e21); stack(e21, e50); stack(price, e50); stack(e50, e200)
        val adx = ind.at(ind.adx14.adx)
        val adxFactor = if (adx.isNaN()) 0.4 else (adx / 30.0).coerceIn(0.4, 1.0)
        val trend = if (seen == 0) 0.0 else (up - down).toDouble() / seen * adxFactor
        directional.add(Raw("Trend confirmation", W_TREND, trend, "EMA stack ${up}up/${down}down, ADX adj ${fmt(adxFactor)}"))

        // Momentum: 10-bar momentum in ATR units
        val mom = ind.at(ind.mom10)
        directional.add(Raw("Momentum", W_MOMENTUM, if (mom.isNaN()) 0.0 else tanh(mom / (atr * 3.0)), "10-bar momentum"))

        // RSI: leaning with RSI, but extremes argue for mean reversion
        val rsi = ind.at(ind.rsi14)
        val rsiBull = when {
            rsi.isNaN() -> 0.0
            rsi > 75.0 -> -0.5
            rsi < 25.0 -> 0.5
            else -> ((rsi - 50.0) / 25.0).coerceIn(-1.0, 1.0)
        }
        directional.add(Raw("RSI", W_RSI, rsiBull, "RSI ${if (rsi.isNaN()) "n/a" else fmt(rsi)}"))

        // MACD: histogram size relative to ATR
        val hist = ind.at(ind.macd.hist)
        directional.add(Raw("MACD", W_MACD, if (hist.isNaN()) 0.0 else tanh(hist / atr * 8.0), "MACD histogram"))

        // EMA structure: slope of EMA21 and price vs EMA9
        val e21Prev = ind.at(ind.ema21, 5)
        val slope = if (e21.isNaN() || e21Prev.isNaN()) 0.0 else tanh((e21 - e21Prev) / (atr * 2.0))
        val pos = if (e9.isNaN()) 0.0 else tanh((price - e9) / atr)
        directional.add(Raw("EMA structure", W_EMA, (slope * 0.7 + pos * 0.3).coerceIn(-1.0, 1.0), "EMA21 slope / price vs EMA9"))

        // VWAP (tick-activity weighted): price above it leans CALL, below leans PUT, measured in ATR units
        val vwapNow = ind.at(ind.vwap)
        val vwapNote = if (ind.hasActivityData) "price vs VWAP (tick-weighted)" else "price vs VWAP (equal-weighted, no tick data)"
        directional.add(Raw("VWAP", W_VWAP, if (vwapNow.isNaN()) 0.0 else tanh((price - vwapNow) / (atr * 2.0)), vwapNote))

        // Support / resistance proximity (bounce bias)
        val levels = ind.levels().filter { it.touches >= 2 }
        val nearSupport = levels.any { it.price <= price + 0.2 * atr && price - it.price <= 0.8 * atr }
        val nearResistance = levels.any { it.price >= price - 0.2 * atr && it.price - price <= 0.8 * atr }
        val sr = when {
            nearSupport && !nearResistance -> 0.8
            nearResistance && !nearSupport -> -0.8
            else -> 0.0
        }
        directional.add(Raw("Support/resistance", W_SR, sr, if (sr > 0) "near support" else if (sr < 0) "near resistance" else "no level nearby"))

        // Candlestick confirmation
        directional.add(Raw("Candlestick", W_CANDLE, PatternDetector.net(ctx.patterns), ctx.patterns.joinToString { it.name }.ifEmpty { "no pattern" }))

        // Breakout / pullback setups from the strategy votes
        val setupVotes = votes.filter { it.strategy == BreakoutStrategy.name || it.strategy == PullbackStrategy.name }
        val setup = setupVotes.sumOf { it.direction.sign }.toDouble() / 2.0
        directional.add(Raw("Breakout/pullback", W_SETUP, setup, "${setupVotes.count { it.direction != Dir.WAIT }} setup strategy vote(s)"))

        // Multi-timeframe agreement
        directional.add(Raw("Multi-timeframe", W_MTF, if (ctx.mtf.available) ctx.mtf.alignment else 0.0, ctx.mtf.summary))

        val dirSum = directional.sumOf { it.weight * it.bull }
        val sign = if (dirSum > 0.0) 1.0 else if (dirSum < 0.0) -1.0 else 0.0
        if (sign == 0.0) {
            return ScoreResult(Dir.WAIT, 50, directional.map { ScoreComponent(it.name, it.weight, it.bull, it.note) })
        }

        val ratio = ind.atrRatio()
        val vol = when {
            ratio.isNaN() -> 0.0
            ratio > 1.8 -> -1.0
            ratio > 1.5 -> -0.4
            ratio < 0.5 -> -0.4
            ratio in 0.7..1.3 -> 0.6
            else -> 0.2
        }
        val regimeAlign = when (ctx.regime.regime) {
            Regime.TREND_UP -> sign
            Regime.TREND_DOWN -> -sign
            Regime.BREAKOUT -> if (ctx.regime.direction.sign.toDouble() == sign) 0.6 else -0.6
            Regime.RANGE -> -0.2
            Regime.HIGH_VOLATILITY -> -1.0
            Regime.LOW_VOLATILITY -> -0.3
            Regime.UNCERTAIN -> -0.6
        }

        val comps = ArrayList<ScoreComponent>()
        var total = 0.0
        for (r in directional) {
            val a = (r.bull * sign).coerceIn(-1.0, 1.0)
            comps.add(ScoreComponent(r.name, r.weight, a, r.note))
            total += r.weight * a
        }
        comps.add(ScoreComponent("Volatility", W_VOLATILITY, vol, "ATR ${if (ratio.isNaN()) "n/a" else fmt(ratio)}x normal"))
        total += W_VOLATILITY * vol
        comps.add(ScoreComponent("Market regime", W_REGIME, regimeAlign.coerceIn(-1.0, 1.0), ctx.regime.regime.name))
        total += W_REGIME * regimeAlign.coerceIn(-1.0, 1.0)

        val score = (50.0 + total / 2.0).coerceIn(0.0, 100.0)
        val dir = if (total < 6.0) Dir.WAIT else if (sign > 0.0) Dir.CALL else Dir.PUT
        return ScoreResult(dir, score.toInt(), comps)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
