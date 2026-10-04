package com.jarvis.assistant.demotrade

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Everything a strategy may look at. Built once per closed candle from data that existed at that moment. */
class AnalysisContext(
    val ind: IndicatorSet,
    val regime: RegimeResult,
    val patterns: List<Pattern>,
    val mtf: MtfResult
)

interface Strategy {
    val id: String
    val name: String
    fun evaluate(ctx: AnalysisContext): StrategyVote
}

private fun vote(s: Strategy, dir: Dir, score: Double, reasons: List<String>): StrategyVote =
    StrategyVote(s.name, dir, score.coerceIn(0.0, 100.0).toInt(), reasons)

private fun noTrade(s: Strategy, why: String): StrategyVote = StrategyVote(s.name, Dir.WAIT, 0, listOf(why))

private fun f(v: Double, digits: Int = 1): String = String.format(java.util.Locale.US, "%.${digits}f", v)

/** A: trade with an established trend. */
object TrendFollowing : Strategy {
    override val id = "trend"
    override val name = "Trend Following"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val dir = when (ctx.regime.regime) { Regime.TREND_UP -> Dir.CALL; Regime.TREND_DOWN -> Dir.PUT; else -> Dir.WAIT }
        if (dir == Dir.WAIT) return noTrade(this, "regime ${ctx.regime.regime} is not a trend")
        val adx = ind.at(ind.adx14.adx)
        val rsi = ind.at(ind.rsi14)
        val reasons = ArrayList<String>()
        reasons.add("EMA 9/21/50 aligned ${if (dir == Dir.CALL) "up" else "down"}")
        var score = 60.0
        score += min(15.0, max(0.0, (adx - 22.0) * 1.2))
        reasons.add("ADX ${f(adx)}")
        val e200 = ind.at(ind.ema200)
        if (!e200.isNaN()) {
            val above = ind.price > e200
            if ((dir == Dir.CALL) == above) { score += 8.0; reasons.add("price on the trend side of EMA200") } else score -= 10.0
        }
        val pdi = ind.at(ind.adx14.plusDi)
        val mdi = ind.at(ind.adx14.minusDi)
        if ((dir == Dir.CALL && pdi > mdi) || (dir == Dir.PUT && mdi > pdi)) { score += 5.0; reasons.add("+DI/-DI agree") }
        if ((dir == Dir.CALL && rsi > 78.0) || (dir == Dir.PUT && rsi < 22.0)) { score -= 12.0; reasons.add("RSI stretched (${f(rsi)})") }
        if (ctx.mtf.available && ctx.mtf.higher == dir) { score += 6.0; reasons.add("higher timeframe agrees") }
        return vote(this, dir, score, reasons)
    }
}

/** B: EMA 9/21 direction confirmed by RSI momentum. */
object EmaRsiMomentum : Strategy {
    override val id = "ema_rsi"
    override val name = "EMA + RSI Momentum"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val e9 = ind.at(ind.ema9)
        val e21 = ind.at(ind.ema21)
        val rsi = ind.at(ind.rsi14)
        val rsiPrev = ind.at(ind.rsi14, 1)
        val atr = ind.atrNow
        if (e9.isNaN() || e21.isNaN() || rsi.isNaN() || rsiPrev.isNaN() || atr.isNaN() || atr <= 0.0) return noTrade(this, "indicators not ready")
        val price = ind.price
        val bull = e9 > e21 && price > e21 && rsi in 52.0..70.0 && rsi > rsiPrev
        val bear = e9 < e21 && price < e21 && rsi in 30.0..48.0 && rsi < rsiPrev
        if (!bull && !bear) return noTrade(this, "no EMA/RSI momentum alignment (RSI ${f(rsi)})")
        val gap = abs(e9 - e21) / atr
        var score = 58.0 + min(14.0, gap * 18.0)
        if (abs(rsi - rsiPrev) >= 2.0) score += 4.0
        val dir = if (bull) Dir.CALL else Dir.PUT
        return vote(this, dir, score, listOf("EMA9 ${if (bull) "above" else "below"} EMA21", "RSI ${f(rsi)} ${if (bull) "rising" else "falling"}"))
    }
}

/** C: MACD cross / histogram expansion. */
object MacdMomentum : Strategy {
    override val id = "macd"
    override val name = "MACD Momentum"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val hist = ind.at(ind.macd.hist)
        val histPrev = ind.at(ind.macd.hist, 1)
        val line = ind.at(ind.macd.line)
        val signal = ind.at(ind.macd.signal)
        val atr = ind.atrNow
        if (hist.isNaN() || histPrev.isNaN() || line.isNaN() || signal.isNaN() || atr.isNaN() || atr <= 0.0) return noTrade(this, "MACD not ready")
        val crossUp = histPrev <= 0.0 && hist > 0.0
        val crossDown = histPrev >= 0.0 && hist < 0.0
        val bull = crossUp || (hist > 0.0 && hist > histPrev && line > signal)
        val bear = crossDown || (hist < 0.0 && hist < histPrev && line < signal)
        if (!bull && !bear) return noTrade(this, "MACD histogram not expanding")
        var score = 55.0 + (if (crossUp || crossDown) 12.0 else 6.0) + min(12.0, abs(hist) / atr * 60.0)
        if ((bull && line > 0.0) || (bear && line < 0.0)) score += 4.0
        if (ctx.regime.regime == Regime.RANGE) score -= 8.0
        val dir = if (bull) Dir.CALL else Dir.PUT
        return vote(this, dir, score, listOf(if (crossUp || crossDown) "MACD histogram crossed zero" else "MACD histogram expanding"))
    }
}

/** D: close beyond the recent range with real expansion (and without a long rejecting wick). */
object BreakoutStrategy : Strategy {
    override val id = "breakout"
    override val name = "Breakout"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val n = ind.n
        val atr = ind.atrNow
        if (n < 25 || atr.isNaN() || atr <= 0.0) return noTrade(this, "not enough candles")
        if (ctx.regime.regime == Regime.HIGH_VOLATILITY) return noTrade(this, "volatility too high for a clean breakout")
        var priorHigh = Double.NEGATIVE_INFINITY
        var priorLow = Double.POSITIVE_INFINITY
        for (i in n - 21 until n - 1) { priorHigh = max(priorHigh, ind.high[i]); priorLow = min(priorLow, ind.low[i]) }
        val c = ind.candles[n - 1]
        val range = c.high - c.low
        val body = abs(c.close - c.open)
        val up = c.close > priorHigh
        val down = c.close < priorLow
        if (!up && !down) return noTrade(this, "price inside the 20-candle range")
        var confirmations = 0
        val reasons = ArrayList<String>()
        reasons.add(if (up) "close above 20-candle high" else "close below 20-candle low")
        if (range >= 1.2 * atr) { confirmations++; reasons.add("range expansion") }
        if (range > 0.0 && body >= 0.55 * range) { confirmations++; reasons.add("strong body") }
        val adx = ind.at(ind.adx14.adx)
        val adxPrev = ind.at(ind.adx14.adx, 3)
        if (!adx.isNaN() && !adxPrev.isNaN() && adx > adxPrev) { confirmations++; reasons.add("ADX rising") }
        val wick = if (up) c.high - max(c.open, c.close) else min(c.open, c.close) - c.low
        if (range > 0.0 && wick > 0.35 * range) return noTrade(this, "breakout candle has a long rejecting wick (possible fake breakout)")
        if (confirmations < 2) return noTrade(this, "breakout lacks confirmation")
        return vote(this, if (up) Dir.CALL else Dir.PUT, 55.0 + confirmations * 7.0, reasons)
    }
}

/** E: bounce / rejection at a support or resistance level that was touched more than once. */
object SupportResistanceRejection : Strategy {
    override val id = "sr_rejection"
    override val name = "Support/Resistance Rejection"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val atr = ind.atrNow
        if (ind.n < 40 || atr.isNaN() || atr <= 0.0) return noTrade(this, "not enough candles")
        val levels = ind.levels().filter { it.touches >= 2 }
        if (levels.isEmpty()) return noTrade(this, "no confirmed support/resistance level")
        val c = ind.candles[ind.n - 1]
        val range = c.high - c.low
        if (range <= 0.0) return noTrade(this, "flat candle")
        val price = ind.price
        val support = levels.filter { it.price <= price + 0.2 * atr && price - it.price <= 0.6 * atr }.maxByOrNull { it.price }
        val resistance = levels.filter { it.price >= price - 0.2 * atr && it.price - price <= 0.6 * atr }.minByOrNull { it.price }
        val bullPattern = ctx.patterns.filter { it.bias > 0 }.maxOfOrNull { it.strength } ?: 0.0
        val bearPattern = ctx.patterns.filter { it.bias < 0 }.maxOfOrNull { it.strength } ?: 0.0
        val lowerWick = min(c.open, c.close) - c.low
        val upperWick = c.high - max(c.open, c.close)
        val rsi = ind.at(ind.rsi14)
        if (support != null && c.close > c.open && (lowerWick >= 0.4 * range || bullPattern >= 0.6)) {
            var score = 58.0 + min(12.0, support.touches * 3.0) + bullPattern * 8.0
            if (!rsi.isNaN() && rsi < 45.0) score += 4.0
            if (ctx.regime.regime == Regime.TREND_DOWN) score -= 12.0
            return vote(this, Dir.CALL, score, listOf("bounce from support (${support.touches} touches)", "bullish rejection candle"))
        }
        if (resistance != null && c.close < c.open && (upperWick >= 0.4 * range || bearPattern >= 0.6)) {
            var score = 58.0 + min(12.0, resistance.touches * 3.0) + bearPattern * 8.0
            if (!rsi.isNaN() && rsi > 55.0) score += 4.0
            if (ctx.regime.regime == Regime.TREND_UP) score -= 12.0
            return vote(this, Dir.PUT, score, listOf("rejection at resistance (${resistance.touches} touches)", "bearish rejection candle"))
        }
        return noTrade(this, "no rejection at a key level")
    }
}

/** F: shallow pullback to EMA21 inside an established trend, resuming in the trend direction. */
object PullbackStrategy : Strategy {
    override val id = "pullback"
    override val name = "Pullback"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val dir = when (ctx.regime.regime) { Regime.TREND_UP -> Dir.CALL; Regime.TREND_DOWN -> Dir.PUT; else -> Dir.WAIT }
        if (dir == Dir.WAIT) return noTrade(this, "pullbacks are only taken inside a trend")
        val n = ind.n
        val e21 = ind.at(ind.ema21)
        val atr = ind.atrNow
        val rsi = ind.at(ind.rsi14)
        if (e21.isNaN() || atr.isNaN() || atr <= 0.0 || rsi.isNaN()) return noTrade(this, "indicators not ready")
        val c = ind.candles[n - 1]
        val near = abs(ind.price - e21) <= 0.5 * atr
        var touched = false
        for (i in n - 3 until n) {
            if (dir == Dir.CALL && ind.low[i] <= e21 + 0.3 * atr) touched = true
            if (dir == Dir.PUT && ind.high[i] >= e21 - 0.3 * atr) touched = true
        }
        val resumed = if (dir == Dir.CALL) c.close > c.open else c.close < c.open
        val rsiOk = if (dir == Dir.CALL) rsi in 40.0..58.0 else rsi in 42.0..60.0
        if (!near || !touched || !resumed || !rsiOk) return noTrade(this, "no completed pullback to EMA21")
        var score = 60.0
        if (rsi in 45.0..55.0) score += 6.0
        val net = PatternDetector.net(ctx.patterns)
        if ((dir == Dir.CALL && net > 0.0) || (dir == Dir.PUT && net < 0.0)) score += 6.0
        val adx = ind.at(ind.adx14.adx)
        if (!adx.isNaN() && adx > 25.0) score += 4.0
        return vote(this, dir, score, listOf("pullback to EMA21 completed", "candle resumed the trend", "RSI ${f(rsi)} reset"))
    }
}

/** G: needs most independent indicator families to agree. */
object MultiConfirmation : Strategy {
    override val id = "multi"
    override val name = "Multi-Confirmation"
    override fun evaluate(ctx: AnalysisContext): StrategyVote {
        val ind = ctx.ind
        val e9 = ind.at(ind.ema9)
        val e21 = ind.at(ind.ema21)
        val e50 = ind.at(ind.ema50)
        val hist = ind.at(ind.macd.hist)
        val rsi = ind.at(ind.rsi14)
        val k = ind.at(ind.stoch.k)
        val d = ind.at(ind.stoch.d)
        val mom = ind.at(ind.mom10)
        val mid = ind.at(ind.bb.mid)
        val price = ind.price
        var bull = 0
        var bear = 0
        if (e9 > e21) bull++ else if (e9 < e21) bear++
        if (e21 > e50) bull++ else if (e21 < e50) bear++
        if (hist > 0.0) bull++ else if (hist < 0.0) bear++
        if (rsi > 52.0 && rsi < 75.0) bull++ else if (rsi < 48.0 && rsi > 25.0) bear++
        if (k > d && k < 80.0) bull++ else if (k < d && k > 20.0) bear++
        if (mom > 0.0) bull++ else if (mom < 0.0) bear++
        if (price > mid) bull++ else if (price < mid) bear++
        val reasons = ArrayList<String>()
        return when {
            bull >= 5 && bear <= 1 -> {
                reasons.add("$bull/7 indicator families bullish")
                vote(this, Dir.CALL, 50.0 + (bull - 4) * 9.0, reasons)
            }
            bear >= 5 && bull <= 1 -> {
                reasons.add("$bear/7 indicator families bearish")
                vote(this, Dir.PUT, 50.0 + (bear - 4) * 9.0, reasons)
            }
            else -> noTrade(this, "indicator families disagree ($bull bullish / $bear bearish)")
        }
    }
}

object StrategyLibrary {
    val all: List<Strategy> = listOf(
        TrendFollowing, EmaRsiMomentum, MacdMomentum, BreakoutStrategy, SupportResistanceRejection, PullbackStrategy, MultiConfirmation,
        BollingerSqueezeBreakout, VwapStrategy, StochasticReversal
    )

    fun enabled(ids: Set<String>): List<Strategy> = all.filter { it.id in ids }

    fun idForName(name: String): String? = all.firstOrNull { it.name == name }?.id
}

data class EnsembleResult(
    val direction: Dir,
    val score: Int,
    val agree: Int,
    val total: Int,
    val opposing: Int,
    val votes: List<StrategyVote>
) {
    val confirmationText: String get() = "$agree/$total"
}

object StrategyEnsemble {
    /** Majority of the non-WAIT votes, penalised for dissent. Ties, thin agreement and heavy dissent all give WAIT. */
    fun combine(votes: List<StrategyVote>, minAgree: Int): EnsembleResult {
        val calls = votes.filter { it.direction == Dir.CALL }
        val puts = votes.filter { it.direction == Dir.PUT }
        val total = votes.size
        if (calls.isEmpty() && puts.isEmpty()) return EnsembleResult(Dir.WAIT, 0, 0, total, 0, votes)
        val group: List<StrategyVote>
        val other: List<StrategyVote>
        val dir: Dir
        if (calls.size > puts.size) { group = calls; other = puts; dir = Dir.CALL }
        else if (puts.size > calls.size) { group = puts; other = calls; dir = Dir.PUT }
        else return EnsembleResult(Dir.WAIT, 0, max(calls.size, puts.size), total, calls.size, votes)
        if (group.size < minAgree || other.size >= group.size) {
            return EnsembleResult(Dir.WAIT, 0, group.size, total, other.size, votes)
        }
        val avg = group.map { it.score }.average()
        val score = (avg - 8.0 * other.size + 3.0 * max(0, group.size - 2)).coerceIn(0.0, 100.0)
        return EnsembleResult(dir, score.toInt(), group.size, total, other.size, votes)
    }
}
