package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs

enum class GuessStrength { WEAK, MEDIUM, STRONG }

/**
 * A labelled GUESS for one future candle (never a signal, never a win probability).
 * [forOpenMs] is the open time of the candle the guess is about; the trade should be placed right at that open.
 */
data class QuickGuess(
    val up: Boolean,
    val strength: GuessStrength,
    val score: Double,
    val reasons: List<String>,
    val forOpenMs: Long
) {
    val label: String get() = if (up) "UP" else "DOWN"
    val arrow: String get() = if (up) "\u2B06" else "\u2B07"
}

/** Vote-based lean from trend, momentum, mean-reversion extremes and the newest candle patterns. Not backtested. */
object QuickGuessEngine {
    const val MIN_CANDLES = 10

    fun guess(candles: List<Candle>, forOpenMs: Long): QuickGuess? {
        if (candles.size < MIN_CANDLES) return null
        val s = PriceSeries(candles.takeLast(200))
        val i = s.size - 1
        var score = 0.0
        val why = ArrayList<Pair<Double, String>>()
        fun vote(v: Double, reason: String) {
            score += v
            why.add(v to reason)
        }

        val e9 = s.ema9[i]; val e21 = s.ema21[i]
        if (!e9.isNaN() && !e21.isNaN() && s.size >= 21) {
            if (e9 > e21) vote(2.0, "EMA9 > EMA21 (uptrend)") else if (e9 < e21) vote(-2.0, "EMA9 < EMA21 (downtrend)")
        }
        val e50 = s.ema50[i]
        if (s.size >= 50 && !e50.isNaN()) {
            if (s.closes[i] > e50) vote(1.0, "price above EMA50") else if (s.closes[i] < e50) vote(-1.0, "price below EMA50")
        }
        if (s.size >= 35) {
            val line = s.macd.first[i]; val sig = s.macd.second[i]
            if (!line.isNaN() && !sig.isNaN()) {
                if (line > sig) vote(1.0, "MACD bullish") else if (line < sig) vote(-1.0, "MACD bearish")
            }
        }
        val rsi = s.rsi14[i]
        if (!rsi.isNaN()) {
            when {
                rsi >= 72 -> vote(-1.5, "RSI ${rsi.toInt()} overbought")
                rsi <= 28 -> vote(1.5, "RSI ${rsi.toInt()} oversold")
                rsi > 55 -> vote(0.5, "RSI ${rsi.toInt()} bullish side")
                rsi < 45 -> vote(-0.5, "RSI ${rsi.toInt()} bearish side")
            }
        }
        val pb = s.percentB[i]
        if (!pb.isNaN()) {
            if (pb > 1.0) vote(-1.0, "price above upper Bollinger band") else if (pb < 0.0) vote(1.0, "price below lower Bollinger band")
        }
        for (p in PriceActionEngine.detect(s)) {
            when (p.bias) {
                PatternBias.BULLISH -> vote(2.0, p.pattern.name.lowercase().replace('_', ' '))
                PatternBias.BEARISH -> vote(-2.0, p.pattern.name.lowercase().replace('_', ' '))
                PatternBias.NEUTRAL -> {}
            }
        }
        if (s.size >= 3) {
            val last3 = (i - 2..i).map { s.up[it] }
            if (last3.all { it }) vote(1.0, "3 green candles in a row") else if (last3.none { it }) vote(-1.0, "3 red candles in a row")
        }

        val up = when {
            score > 0 -> true
            score < 0 -> false
            else -> candles.last().close >= candles.last().open
        }
        val strength = when {
            abs(score) >= 5.0 -> GuessStrength.STRONG
            abs(score) >= 2.5 -> GuessStrength.MEDIUM
            else -> GuessStrength.WEAK
        }
        val top = why.filter { if (up) it.first > 0 else it.first < 0 }
            .sortedByDescending { abs(it.first) }.take(3).map { it.second }
        return QuickGuess(up, strength, score, top, forOpenMs)
    }
}
