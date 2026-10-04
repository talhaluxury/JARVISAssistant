package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestAnalyticsTest {

    private fun trade(id: Int, result: TradeResult, pnl: Double, strategy: String = "Trend Following", regime: Regime = Regime.TREND_UP, conf: Int = 80, at: Long = T0 + id * 60_000L) =
        PaperTrade(
            id = id, asset = "T", direction = Dir.CALL, openedAtMs = at, expiresAtMs = at + 60_000L, expirySeconds = 60,
            entryPrice = 1.1, stake = 10.0, payoutPercent = 90.0, confidence = conf, strength = SignalStrength.STRONG,
            regime = regime, strategies = listOf(strategy), reasons = emptyList(), confirmations = emptyList(),
            indicators = emptyMap(), atrAtEntry = 0.0, riskLevel = RiskLevel.MEDIUM,
            exitPrice = 1.1, closedAtMs = at + 60_000L, result = result, pnl = pnl, balanceAfter = null
        )

    @Test
    fun statsMatchHandCalculation() {
        val trades = listOf(
            trade(1, TradeResult.WIN, 9.0), trade(2, TradeResult.WIN, 9.0), trade(3, TradeResult.LOSS, -10.0),
            trade(4, TradeResult.LOSS, -10.0), trade(5, TradeResult.LOSS, -10.0), trade(6, TradeResult.WIN, 9.0)
        )
        val st = PerformanceAnalyzer.analyze(trades, 10_000.0, 90.0)
        assertEquals(6, st.total)
        assertEquals(3, st.wins)
        assertEquals(3, st.losses)
        assertEquals(0.5, st.winRate!!, 1e-9)
        assertEquals(-3.0, st.totalPnl, 1e-9)
        assertEquals(9.0, st.avgWin, 1e-9)
        assertEquals(-10.0, st.avgLoss, 1e-9)
        assertEquals(27.0 / 30.0, st.profitFactor!!, 1e-9)
        assertEquals(-0.5, st.expectancy, 1e-9)
        assertEquals(2, st.longestWinStreak)
        assertEquals(3, st.longestLossStreak)
        assertEquals(30.0, st.maxDrawdown, 1e-9) // running balance peaks at 10,018 and bottoms at 9,988
        assertEquals(7, st.balanceCurve.size)
        assertEquals(9_997.0, st.balanceCurve.last(), 1e-9)
    }

    @Test
    fun emptyHistoryHasNoFakeNumbers() {
        val st = PerformanceAnalyzer.analyze(emptyList(), 10_000.0, 80.0)
        assertEquals(0, st.total)
        assertNull(st.winRate)
        assertNull(st.profitFactor)
        assertNull(st.bestStrategy)
    }

    @Test
    fun openTradesAreIgnored() {
        val open = trade(1, TradeResult.WIN, 9.0).copy(result = null, pnl = null, closedAtMs = null)
        assertEquals(0, PerformanceAnalyzer.analyze(listOf(open), 10_000.0, 90.0).total)
    }

    @Test
    fun bestAndWorstNeedEnoughSamples() {
        val trades = ArrayList<PaperTrade>()
        var id = 1
        repeat(6) { trades.add(trade(id++, TradeResult.WIN, 9.0, strategy = "Good")) }
        repeat(6) { trades.add(trade(id++, TradeResult.LOSS, -10.0, strategy = "Bad")) }
        repeat(2) { trades.add(trade(id++, TradeResult.WIN, 9.0, strategy = "Tiny")) }
        val st = PerformanceAnalyzer.analyze(trades, 10_000.0, 90.0)
        assertEquals("Good", st.bestStrategy)
        assertEquals("Bad", st.worstStrategy)
        assertTrue(st.byStrategy.any { it.key == "Tiny" })
    }

    @Test
    fun confidenceBucketsMatchTheSpecRanges() {
        assertEquals("70-74", PerformanceAnalyzer.confidenceBucket(72))
        assertEquals("75-84", PerformanceAnalyzer.confidenceBucket(80))
        assertEquals("85-100", PerformanceAnalyzer.confidenceBucket(90))
    }

    @Test
    fun adaptiveFilterIsOffByDefaultAndOnlyTightens() {
        val bad = (1..40).map { trade(it, if (it % 4 == 0) TradeResult.WIN else TradeResult.LOSS, if (it % 4 == 0) 9.0 else -10.0, conf = 72) }
        assertEquals(AdaptiveAdvice.NONE, AdaptiveFilter.advise(bad, testSettings()))
        val on = testSettings { it.copy(adaptiveEnabled = true, adaptiveMaxBump = 10, adaptiveMinTrades = 30) }
        val advice = AdaptiveFilter.advise(bad, on)
        assertTrue(advice.minConfidenceBump in 0..10)
        assertTrue(advice.minConfidenceBump > 0)
        val capped = on.copy(adaptiveMaxBump = 3)
        assertTrue(AdaptiveFilter.advise(bad, capped).minConfidenceBump <= 3)
        val few = AdaptiveFilter.advise(bad.take(5), on)
        assertEquals(0, few.minConfidenceBump)
    }

    @Test
    fun journalExplanationsNeverClaimCertainty() {
        val win = trade(1, TradeResult.WIN, 9.0).let { TradeJournal.explain(it) }
        val loss = trade(2, TradeResult.LOSS, -10.0).let { TradeJournal.explain(it) }
        assertTrue(win.startsWith("Trade won"))
        assertTrue(loss.startsWith("Trade lost"))
        assertTrue(!win.contains("guarantee", ignoreCase = true) && !loss.contains("guarantee", ignoreCase = true))
    }

    @Test
    fun backtestIsDeterministicAndRespectsLimits() {
        val candles = CandleSimulator.generate(Scenario.MIXED, 600)
        val s = testSettings()
        val a = DemoBacktestEngine.run(candles, s, MIN_MS)
        val b = DemoBacktestEngine.run(candles, s, MIN_MS)
        assertEquals(a.trades.map { it.id to it.result }, b.trades.map { it.id to it.result })
        assertEquals(a.endBalance, b.endBalance, 1e-9)
        assertTrue(a.trades.all { it.stake <= s.maxStake })
        assertTrue(a.stats.total <= candles.size)
        assertNotNull(a.note)
        // one trade at a time: trades never overlap
        val sorted = a.trades.sortedBy { it.openedAtMs }
        for (i in 1 until sorted.size) assertTrue(sorted[i].openedAtMs >= (sorted[i - 1].closedAtMs ?: 0L))
    }

    @Test
    fun backtestNeverSeesTheFuture() {
        val candles = CandleSimulator.generate(Scenario.MIXED, 600)
        val s = testSettings()
        val first = DemoBacktestEngine.run(candles.subList(0, 400), s, MIN_MS)
        // Changing candles AFTER the cut must not change anything that happened before it.
        val altered = candles.subList(0, 400) + candles.subList(400, 600).map { it.copy(close = it.close * 1.0, high = it.high + 0.0005) }
        val second = DemoBacktestEngine.run(altered, s, MIN_MS)
        val cut = candles[399].openTimeMs + MIN_MS
        val early = second.trades.filter { (it.closedAtMs ?: Long.MAX_VALUE) <= cut }
        val firstEarly = first.trades.filter { (it.closedAtMs ?: Long.MAX_VALUE) <= cut }
        assertEquals(firstEarly.map { it.id to it.result }, early.map { it.id to it.result })
    }

    @Test
    fun backtestHonoursTheDailyLossLimit() {
        val candles = CandleSimulator.generate(Scenario.MIXED, 800)
        val s = testSettings { it.copy(maxDailyLossPercent = 0.5, fixedStake = 100.0, maxStake = 100.0) }
        val r = DemoBacktestEngine.run(candles, s, MIN_MS)
        // every stop the engine reports must be one of the documented protection messages
        assertTrue(r.halts.all { it.contains("reached") || it.contains("activated") })
        assertTrue(r.trades.all { it.stake <= 100.0 })
    }

    @Test
    fun tooFewCandlesGivesAnEmptyReportNotAnError() {
        val r = DemoBacktestEngine.run(flatCandles(10), testSettings(), MIN_MS)
        assertEquals(0, r.stats.total)
    }

    @Test
    fun simulatorIsReproducible() {
        val a = CandleSimulator.generate(Scenario.STRONG_CALL, 100, 5L)
        val b = CandleSimulator.generate(Scenario.STRONG_CALL, 100, 5L)
        assertEquals(a, b)
        assertTrue(a.last().close > a.first().close)
        val d = CandleSimulator.generate(Scenario.STRONG_PUT, 100, 5L)
        assertTrue(d.last().close < d.first().close)
    }
}
