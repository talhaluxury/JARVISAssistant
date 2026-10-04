package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class BacktestReport(
    val trades: List<PaperTrade>,
    val stats: TradeStats,
    val candlesTested: Int,
    val signalsEvaluated: Int,
    val waits: Int,
    val blocked: Map<String, Int>,
    val halts: List<String>,
    val startBalance: Double,
    val endBalance: Double,
    val note: String
)

/**
 * Replays the exact live pipeline (same validator, indicators, regime, strategies, scoring, consensus, risk manager and
 * paper executor) over historical candles.
 *
 * No look-ahead: at candle i the pipeline only receives candles[0..i]. A trade opens at the close of candle i and is
 * settled at the close of the first candle whose close time is at or after the expiry time.
 * The AI layer is not replayed (an LLM cannot be replayed honestly on old data), so backtests run with AI off.
 * One trade at a time. The simulated clock is the candle clock, so cooldowns / hourly / daily limits behave as they would live.
 */
object DemoBacktestEngine {

    fun run(candles: List<Candle>, settings: DemoSettings, candleMs: Long, window: Int = 400): BacktestReport {
        val s = settings.coerced().copy(useAi = false)
        val startMs = candles.firstOrNull()?.openTimeMs ?: 0L
        var acct = DemoAccount.fresh(s.initialBalance, startMs)
        val closed = ArrayList<PaperTrade>()
        var open: PaperTrade? = null
        var openExitIdx = -1
        val blocked = LinkedHashMap<String, Int>()
        val halts = ArrayList<String>()
        var evaluated = 0
        var waits = 0
        var nextId = 1
        val expiryCandles = max(1, ceil(s.expirySeconds * 1000.0 / candleMs.toDouble()).toInt())

        fun bump(key: String) { blocked[key] = (blocked[key] ?: 0) + 1 }
        fun noteHalt(index: Int) {
            val h = RiskManager.checkHalts(acct, s)
            if (h != HaltReason.NONE && acct.halt == HaltReason.NONE) {
                acct = acct.copy(halt = h)
                halts.add("${h.message} (candle #$index)")
            }
        }

        if (candleMs > 0L && candles.size >= s.minCandles) {
            for (i in (s.minCandles - 1) until candles.size) {
                val nowMs = candles[i].openTimeMs + candleMs
                acct = acct.rolled(nowMs)

                val o = open
                if (o != null && i >= openExitIdx) {
                    val exit = candles[i].close
                    val result = PaperTradeExecutor.decide(o.direction, o.entryPrice, exit)
                    val settled = PaperTradeExecutor.settle(acct, o, exit, result, nowMs)
                    acct = settled.first
                    val t = settled.second
                    closed.add(t.copy(explanation = TradeJournal.explain(t)))
                    open = null
                    noteHalt(i)
                }
                if (open != null) continue

                val from = max(0, i - window + 1)
                val sub = candles.subList(from, i + 1)
                val p = SignalPipeline.analyze(sub, s, nowMs, candleMs, checkFresh = false)
                evaluated++
                val sig = SignalPipeline.finalize(p, null, s, "backtest", p.lastClose, nowMs)
                if (sig.direction == Dir.WAIT) {
                    waits++
                    bump(sig.blockReason?.substringBefore(':') ?: "Wait")
                    continue
                }
                val advice = AdaptiveFilter.advise(closed, s)
                val decision = RiskManager.evaluate(sig, acct, s, nowMs, 0, advice)
                if (!decision.approved) {
                    bump("Risk")
                    noteHalt(i)
                    continue
                }
                val opened = PaperTradeExecutor.open(acct, nextId++, sig.copy(stake = decision.stake), decision.stake, s, nowMs)
                acct = opened.first
                open = opened.second
                openExitIdx = i + expiryCandles
            }
        }
        val stats = PerformanceAnalyzer.analyze(closed, s.initialBalance, s.payoutPercent)
        return BacktestReport(
            trades = closed,
            stats = stats,
            candlesTested = max(0, candles.size - (s.minCandles - 1)),
            signalsEvaluated = evaluated,
            waits = waits,
            blocked = blocked,
            halts = halts,
            startBalance = s.initialBalance,
            endBalance = acct.balance,
            note = "Walk-forward on historical candles: no future data, AI layer off, one trade at a time. " +
                "Past results do not predict future results; break-even win rate at this payout is " +
                String.format(java.util.Locale.US, "%.1f%%", s.breakEvenWinRate * 100.0) + "."
        )
    }
}

enum class Scenario(val label: String) {
    STRONG_CALL("Strong uptrend"),
    STRONG_PUT("Strong downtrend"),
    SIDEWAYS("Sideways market"),
    HIGH_VOLATILITY("High-volatility shock"),
    MIXED("Mixed random market")
}

/** Artificial candle sequences for SIMULATION MODE and the unit tests. Seeded, so every run is reproducible. */
object CandleSimulator {
    const val CANDLE_MS = 60_000L
    const val START_MS = 1_700_000_000_000L
    private const val START_PRICE = 1.10000

    private fun walk(
        rnd: Random, startPrice: Double, startTime: Long, n: Int,
        drift: Double, sigma: Double, revert: Double = 0.0, anchor: Double = startPrice
    ): List<Candle> {
        val out = ArrayList<Candle>(n)
        var p = startPrice
        for (i in 0 until n) {
            val o = p
            var c = o * (1.0 + drift + rnd.nextGaussian() * sigma) + revert * (anchor - o)
            if (c <= 0.0) c = o
            val h = max(o, c) + abs(rnd.nextGaussian()) * sigma * 0.6 * o
            val l = min(o, c) - abs(rnd.nextGaussian()) * sigma * 0.6 * o
            out.add(Candle(startTime + i * CANDLE_MS, o, h, l, c))
            p = c
        }
        return out
    }

    private fun chain(parts: List<(Random, Double, Long) -> List<Candle>>, rnd: Random): List<Candle> {
        val out = ArrayList<Candle>()
        var price = START_PRICE
        var time = START_MS
        for (part in parts) {
            val seg = part(rnd, price, time)
            if (seg.isEmpty()) continue
            out.addAll(seg)
            price = seg.last().close
            time = seg.last().openTimeMs + CANDLE_MS
        }
        return out
    }

    fun generate(scenario: Scenario, count: Int = 400, seed: Long = 7L): List<Candle> {
        val rnd = Random(seed)
        return when (scenario) {
            Scenario.STRONG_CALL -> walk(rnd, START_PRICE, START_MS, count, 0.00015, 0.00007)
            Scenario.STRONG_PUT -> walk(rnd, START_PRICE, START_MS, count, -0.00015, 0.00007)
            Scenario.SIDEWAYS -> walk(rnd, START_PRICE, START_MS, count, 0.0, 0.00008, 0.3)
            Scenario.HIGH_VOLATILITY -> chain(
                listOf(
                    { r: Random, p: Double, t: Long -> walk(r, p, t, max(1, count - 150), 0.0, 0.00004, 0.2) },
                    { r: Random, p: Double, t: Long -> walk(r, p, t, 150, 0.0, 0.0012) }
                ), rnd
            )
            Scenario.MIXED -> {
                val seg = max(50, count / 5)
                chain(
                    listOf(
                        { r: Random, p: Double, t: Long -> walk(r, p, t, seg, 0.00010, 0.00008) },
                        { r: Random, p: Double, t: Long -> walk(r, p, t, seg, 0.0, 0.00008, 0.3, p) },
                        { r: Random, p: Double, t: Long -> walk(r, p, t, seg, -0.00010, 0.00008) },
                        { r: Random, p: Double, t: Long -> walk(r, p, t, seg, 0.00010, 0.00008) },
                        { r: Random, p: Double, t: Long -> walk(r, p, t, seg, 0.0, 0.00008, 0.3, p) }
                    ), rnd
                )
            }
        }
    }
}
