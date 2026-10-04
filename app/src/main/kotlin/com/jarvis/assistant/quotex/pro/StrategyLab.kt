package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.domain.Candle

/** A user-defined condition evaluated on the newest closed candle of a series. */
sealed class RuleCondition(val label: String) {
    abstract fun holds(s: PriceSeries): Boolean

    class EmaAbove(val fast: Int, val slow: Int) : RuleCondition("EMA$fast > EMA$slow") {
        override fun holds(s: PriceSeries): Boolean {
            val i = s.size - 1
            if (i < 0 || s.size < slow) return false // EMA is not warmed up before `slow` candles
            val a = ema(s, fast) ?: return false
            val b = ema(s, slow) ?: return false
            return !a[i].isNaN() && !b[i].isNaN() && a[i] > b[i]
        }
        private fun ema(s: PriceSeries, p: Int): DoubleArray? = when (p) {
            9 -> s.ema9; 21 -> s.ema21; 50 -> s.ema50; 100 -> s.ema100; 200 -> s.ema200; else -> null
        }
    }

    class EmaBelow(val fast: Int, val slow: Int) : RuleCondition("EMA$fast < EMA$slow") {
        override fun holds(s: PriceSeries): Boolean {
            val i = s.size - 1
            if (i < 0 || s.size < slow) return false
            val a = pick(s, fast) ?: return false
            val b = pick(s, slow) ?: return false
            return !a[i].isNaN() && !b[i].isNaN() && a[i] < b[i]
        }
        private fun pick(s: PriceSeries, p: Int): DoubleArray? = when (p) {
            9 -> s.ema9; 21 -> s.ema21; 50 -> s.ema50; 100 -> s.ema100; 200 -> s.ema200; else -> null
        }
    }

    class RsiBetween(val lo: Double, val hi: Double) : RuleCondition("RSI $lo-$hi") {
        override fun holds(s: PriceSeries): Boolean {
            val r = s.rsi14.lastOrNull() ?: return false
            return !r.isNaN() && r in lo..hi
        }
    }

    class AdxAtLeast(val min: Double) : RuleCondition("ADX >= $min") {
        override fun holds(s: PriceSeries): Boolean {
            val a = s.adx14.lastOrNull() ?: return false
            return !a.isNaN() && a >= min
        }
    }
}

enum class LabStatus { DRAFT, BACKTESTED, WALK_FORWARD_PASSED, PAPER_TESTING, ENABLED }

data class StrategyRule(
    val id: String,
    val name: String,
    val conditions: List<RuleCondition>,
    val status: LabStatus = LabStatus.DRAFT,
    val callSide: Boolean = true
) {
    fun matches(s: PriceSeries): Boolean = conditions.isNotEmpty() && conditions.all { it.holds(s) }
}

data class ValidationSummary(
    val outOfSampleTrades: Int,
    val outOfSampleExpectancy: Double,
    val walkForwardWindowsPositive: Int,
    val walkForwardWindows: Int,
    val robustness: Robustness,
    val paperTrades: Int,
    val paperExpectancy: Double
)

data class GateResult(val allowed: Boolean, val reasons: List<String>)

/** A new or re-optimised strategy is never switched on automatically: it must pass every step below first. */
object StrategyDeployGate {
    const val MIN_OOS_TRADES = 100
    const val MIN_PAPER_TRADES = 30

    fun canEnable(v: ValidationSummary): GateResult {
        val why = ArrayList<String>()
        if (v.outOfSampleTrades < MIN_OOS_TRADES) why += "out-of-sample sample too small (${v.outOfSampleTrades}/$MIN_OOS_TRADES)"
        if (v.outOfSampleExpectancy <= 0.0) why += "out-of-sample expectancy is not positive"
        if (v.walkForwardWindows < 3) why += "walk-forward needs at least 3 windows"
        else if (v.walkForwardWindowsPositive * 3 < v.walkForwardWindows * 2) why += "fewer than 2/3 of walk-forward windows are profitable"
        if (v.robustness != Robustness.ROBUST) why += "parameter robustness is ${v.robustness}"
        if (v.paperTrades < MIN_PAPER_TRADES) why += "paper observation too short (${v.paperTrades}/$MIN_PAPER_TRADES)"
        else if (v.paperExpectancy <= 0.0) why += "paper expectancy is not positive"
        return GateResult(why.isEmpty(), why)
    }
}


data class LabSegment(val name: String, val trades: Int, val wins: Int, val expectancy: Double)
data class LabReport(
    val rule: String,
    val segments: List<LabSegment>,
    val pnl: List<Double>,
    val walkForwardPositive: Int,
    val walkForwardWindows: Int,
    val monteCarlo: MonteCarloResult,
    val warnings: List<String>
)

/**
 * Chronological backtest of a user rule. At candle i the rule only sees candles 0..i (PriceSeries.window uses an
 * exclusive end index), the trade settles at candle i+expiry, and trades never overlap. Segments are TRAIN 60% /
 * VALIDATION 20% / TEST 20% by time, never shuffled. Win = +payout, loss = -1, draw = 0.
 */
object LabBacktester {
    fun run(
        rule: StrategyRule, candles: List<Candle>, expiryCandles: Int = 4, payout: Double = 0.85,
        warmup: Int = 60, cap: Int = 400, windows: Int = 5
    ): LabReport {
        val warnings = ArrayList<String>()
        val trades = ArrayList<Pair<Int, Double>>() // (candle index, pnl)
        var i = warmup
        while (i + expiryCandles < candles.size) {
            val series = PriceSeries.window(candles, i + 1, cap)
            if (rule.matches(series)) {
                val entry = candles[i].close
                val exit = candles[i + expiryCandles].close
                val pnl = when {
                    exit == entry -> 0.0
                    (exit > entry) == rule.callSide -> payout
                    else -> -1.0
                }
                trades.add(i to pnl)
                i += expiryCandles + 1
            } else i++
        }
        val n = candles.size
        fun seg(name: String, from: Int, to: Int): LabSegment {
            val t = trades.filter { it.first in from until to }.map { it.second }
            return LabSegment(name, t.size, t.count { it > 0.0 }, if (t.isEmpty()) 0.0 else t.average())
        }
        val segments = listOf(
            seg("TRAIN", 0, n * 6 / 10), seg("VALIDATION", n * 6 / 10, n * 8 / 10), seg("TEST", n * 8 / 10, n + 1)
        )
        var pos = 0
        for (w in 0 until windows) {
            val t = trades.filter { it.first in (n * w / windows) until (n * (w + 1) / windows) }.map { it.second }
            if (t.isNotEmpty() && t.average() > 0.0) pos++
        }
        val pnl = trades.map { it.second }
        if (pnl.size < 30) warnings.add("Only ${pnl.size} trades - too few to trust")
        val test = segments[2]
        val train = segments[0]
        if (train.expectancy > 0.0 && test.expectancy <= 0.0) warnings.add("Profitable in TRAIN but not in TEST - likely overfit")
        if (train.trades > 0 && test.trades > 0 && train.expectancy - test.expectancy > 0.3) warnings.add("Large TRAIN-to-TEST drop - unstable")
        val mc = MonteCarlo.simulate(pnl)
        warnings.addAll(mc.warnings)
        return LabReport(rule.name, segments, pnl, pos, windows, mc, warnings.distinct())
    }
}


/** Parameter sweep: the same rule with each parameter nudged one step. An edge that vanishes next door is overfit. */
fun LabBacktester.sweep(spec: RuleSpec, candles: List<Candle>, expiryCandles: Int = 4, payout: Double = 0.85): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    for ((label, variant) in spec.neighbours()) {
        val rep = LabBacktester.run(variant.toRule(), candles, expiryCandles, payout, cap = 300)
        if (rep.pnl.size >= 10) out[label] = rep.pnl.average()
    }
    return out
}
