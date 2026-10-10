package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs

enum class GuessStrength { WEAK, MEDIUM, STRONG }

/**
 * A labelled GUESS for one future candle (never a signal, never a win probability).
 * [forOpenMs] is the open time of the candle the guess is about; the trade should be placed right at that open.
 * [brief] is a compact data summary for the optional AI second opinion.
 */
data class QuickGuess(
    val up: Boolean,
    val strength: GuessStrength,
    val score: Double,
    val reasons: List<String>,
    val forOpenMs: Long,
    val regime: String = "",
    val agreeing: Int = 0,
    val opposing: Int = 0,
    val brief: String = "",
    /** Signed, regime-weighted vote per signal (key -> value); what the [GuessLearner] trains on. */
    val features: Map<String, Double> = emptyMap(),
    /** The learner's own probability that the next candle is green (null until it exists). Not a promise. */
    val learnedUp: Double? = null
) {
    val label: String get() = if (up) "UP" else "DOWN"
    val arrow: String get() = if (up) "\u2B06" else "\u2B07"
}

/**
 * Vote-based lean from trend, momentum, mean-reversion extremes, candle patterns, a higher-timeframe check and a
 * trend/range regime filter. STRONG is deliberately hard to earn: a high score, several agreeing votes, almost no
 * opposing votes, a trending-enough market and a higher-timeframe that does not disagree. Not backtested.
 */
object QuickGuessEngine {
    const val MIN_CANDLES = 10
    private const val STRONG_SCORE = 7.0
    private const val MEDIUM_SCORE = 3.5

    private class Vote(val key: String, val value: Double, val reason: String, val trend: Boolean)

    fun guess(candles: List<Candle>, forOpenMs: Long): QuickGuess? {
        if (candles.size < MIN_CANDLES) return null
        val s = PriceSeries(candles.takeLast(200))
        val i = s.size - 1
        val votes = ArrayList<Vote>()
        fun trendVote(key: String, v: Double, reason: String) { votes.add(Vote(key, v, reason, true)) }
        fun reversionVote(key: String, v: Double, reason: String) { votes.add(Vote(key, v, reason, false)) }

        // ---- regime: efficiency ratio of the last 20 closes (1 = straight line, 0 = pure chop) + ADX --------------
        var er = Double.NaN
        if (s.size >= 21) {
            val net = abs(s.closes[i] - s.closes[i - 20])
            var path = 0.0
            for (k in i - 19..i) path += abs(s.closes[k] - s.closes[k - 1])
            er = if (path > 0) net / path else 0.0
        }
        val adx = s.adx14[i]
        val trending = (!er.isNaN() && er >= 0.40) || (!adx.isNaN() && adx >= 25)
        val choppy = (!er.isNaN() && er < 0.20) && (adx.isNaN() || adx < 20)
        val regime = when {
            trending -> "TRENDING"
            choppy -> "CHOPPY"
            else -> "MIXED"
        }

        // ---- trend votes -------------------------------------------------------------------------------------------
        val e9 = s.ema9[i]; val e21 = s.ema21[i]
        if (!e9.isNaN() && !e21.isNaN() && s.size >= 21) {
            if (e9 > e21) trendVote("ema9_21", 2.0, "EMA9 > EMA21 (uptrend)") else if (e9 < e21) trendVote("ema9_21", -2.0, "EMA9 < EMA21 (downtrend)")
        }
        val e50 = s.ema50[i]
        if (s.size >= 50 && !e50.isNaN()) {
            if (s.closes[i] > e50) trendVote("ema50", 1.0, "price above EMA50") else if (s.closes[i] < e50) trendVote("ema50", -1.0, "price below EMA50")
        }
        if (s.size >= 35) {
            val line = s.macd.first[i]; val sig = s.macd.second[i]
            if (!line.isNaN() && !sig.isNaN()) {
                if (line > sig) trendVote("macd", 1.0, "MACD bullish") else if (line < sig) trendVote("macd", -1.0, "MACD bearish")
            }
        }
        if (s.size >= 3) {
            val last3 = (i - 2..i).map { s.up[it] }
            if (last3.all { it }) trendVote("three_candles", 1.0, "3 green candles in a row") else if (last3.none { it }) trendVote("three_candles", -1.0, "3 red candles in a row")
        }
        // Higher timeframe: blocks of 5 candles, close vs EMA8 of block closes.
        var htfNote = "n/a"
        var htfUp: Boolean? = null
        if (s.size >= 45) {
            val blocks = ArrayList<Double>()
            var end = s.size
            while (end - 5 >= 0 && blocks.size < 12) { blocks.add(0, s.closes[end - 1]); end -= 5 }
            if (blocks.size >= 9) {
                val ema = com.jarvis.assistant.quotex.analysis.Indicators.ema(blocks.toDoubleArray(), 8)
                val last = blocks.last(); val m = ema.last()
                if (!m.isNaN() && last != m) {
                    htfUp = last > m
                    htfNote = if (last > m) "up" else "down"
                    trendVote("htf", if (last > m) 1.5 else -1.5, "bigger timeframe is $htfNote")
                }
            }
        }

        // ---- mean-reversion votes ----------------------------------------------------------------------------------
        val rsi = s.rsi14[i]
        if (!rsi.isNaN()) {
            when {
                rsi >= 72 -> reversionVote("rsi", -1.5, "RSI ${rsi.toInt()} overbought")
                rsi <= 28 -> reversionVote("rsi", 1.5, "RSI ${rsi.toInt()} oversold")
            }
        }
        val pb = s.percentB[i]
        if (!pb.isNaN()) {
            if (pb > 1.0) reversionVote("bollinger", -1.0, "above upper Bollinger band") else if (pb < 0.0) reversionVote("bollinger", 1.0, "below lower Bollinger band")
        }
        if (s.size >= 20) {
            val k = s.stochastic.first[i]; val d = s.stochastic.second[i]
            if (!k.isNaN() && !d.isNaN()) {
                if (k > 85 && k < d) reversionVote("stoch", -1.0, "stochastic turning down from high")
                else if (k < 15 && k > d) reversionVote("stoch", 1.0, "stochastic turning up from low")
            }
        }

        // ---- candle patterns (always counted) ----------------------------------------------------------------------
        for (p in PriceActionEngine.detect(s)) {
            val name = p.pattern.name.lowercase().replace('_', ' ')
            when (p.bias) {
                PatternBias.BULLISH -> reversionVote("pat_${p.pattern.name.lowercase()}", 2.0, name)
                PatternBias.BEARISH -> reversionVote("pat_${p.pattern.name.lowercase()}", -2.0, name)
                PatternBias.NEUTRAL -> {}
            }
        }

        // ---- regime weighting: trend signals count more in a trend, reversal signals more in a range ---------------
        val trendW = when (regime) { "TRENDING" -> 1.3; "CHOPPY" -> 0.4; else -> 1.0 }
        val revW = when (regime) { "TRENDING" -> 0.5; "CHOPPY" -> 1.4; else -> 1.0 }
        var score = 0.0
        for (v in votes) score += v.value * (if (v.trend) trendW else revW)

        val up = when {
            score > 0 -> true
            score < 0 -> false
            else -> candles.last().close >= candles.last().open
        }
        val agreeing = votes.count { (it.value > 0) == up && it.value != 0.0 }
        val opposing = votes.count { (it.value > 0) != up && it.value != 0.0 }
        val opposingWeight = votes.filter { (it.value > 0) != up }.sumOf { abs(it.value) }
        val htfAgrees = htfUp == null || htfUp == up

        val strong = abs(score) >= STRONG_SCORE && agreeing >= 4 && opposingWeight <= 1.0 && regime != "CHOPPY" && htfAgrees
        val strength = when {
            strong -> GuessStrength.STRONG
            abs(score) >= MEDIUM_SCORE && regime != "CHOPPY" -> GuessStrength.MEDIUM
            else -> GuessStrength.WEAK
        }
        val top = votes.filter { if (up) it.value > 0 else it.value < 0 }
            .sortedByDescending { abs(it.value) }.take(3).map { it.reason }

        val brief = buildString {
            val tail = candles.takeLast(30)
            appendLine("candles (oldest to newest, U=green D=red): " + tail.joinToString("") { if (it.close >= it.open) "U" else "D" })
            appendLine("last 8 candles OHLC:")
            for (c in candles.takeLast(8)) appendLine("  o=${c.open} h=${c.high} l=${c.low} c=${c.close}")
            appendLine("EMA9=${fmt(e9)} EMA21=${fmt(e21)} EMA50=${fmt(e50)} RSI14=${fmt(rsi)} ADX14=${fmt(adx)} efficiency=${fmt(er)}")
            appendLine("regime=$regime higher-timeframe=$htfNote")
            appendLine("local engine says: ${if (up) "UP" else "DOWN"} ${strength.name} (score ${"%.1f".format(score)}); reasons: ${top.joinToString(", ")}")
        }
        val features = HashMap<String, Double>()
        for (v in votes) features[v.key] = (features[v.key] ?: 0.0) + v.value * (if (v.trend) trendW else revW)
        return QuickGuess(up, strength, score, top, forOpenMs, regime, agreeing, opposing, brief, features)
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "n/a" else "%.5f".format(v)
}
