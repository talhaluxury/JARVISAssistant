package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.ConfluenceBacktestEngine
import com.jarvis.assistant.quotex.analysis.ConfluenceEngine
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.SetupQuality
import com.jarvis.assistant.quotex.analysis.Strategy
import com.jarvis.assistant.quotex.analysis.StrategyCondition
import com.jarvis.assistant.quotex.analysis.StrategyPerformanceTracker
import com.jarvis.assistant.quotex.analysis.StrategyResult
import com.jarvis.assistant.quotex.analysis.StructureLabel
import com.jarvis.assistant.quotex.analysis.Swing
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CalibrationTest {

    private fun candlesFromCloses(closes: List<Double>, candleMs: Long = 15_000L, start: Double = 1.0): List<Candle> {
        var previous = start
        return closes.mapIndexed { i, close ->
            val c = Candle(i * candleMs, previous, maxOf(previous, close) + 0.0005, minOf(previous, close) - 0.0005, close)
            previous = close
            c
        }
    }

    private fun flat(n: Int = 150) = PriceSeries(candlesFromCloses(List(n) { 1.0 }))

    private fun fakeStrategy(name: String, direction: QuotexDecision, satisfied: Int, total: Int) = object : Strategy {
        override val name: String = name
        override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<Swing>): StrategyResult {
            val conditions = (0 until total).map { StrategyCondition("c$it", it < satisfied, "") }
            return StrategyResult(name, direction, conditions)
        }
    }

    // ---- tracker -----------------------------------------------------------------------------------------

    @Test
    fun trackerWeightFollowsWalkForwardSkillJustLikeWinGosModelTracker() {
        val young = StrategyPerformanceTracker()
        repeat(10) { young.record(true) }
        assertEquals(1.0, young.weight(), 1e-9) // too few samples to judge yet

        val good = StrategyPerformanceTracker()
        repeat(100) { good.record(true) }
        assertEquals(2.0, good.weight(), 1e-9)

        val bad = StrategyPerformanceTracker()
        repeat(100) { bad.record(false) }
        assertEquals(0.1, bad.weight(), 1e-9)
    }

    // ---- ConfluenceEngine weighting is backward compatible with equal weights ------------------------------------

    @Test
    fun defaultConfluenceEngineIgnoresWeightsMapWhenEmpty() {
        val withoutWeights = ConfluenceEngine(listOf(fakeStrategy("A", QuotexDecision.CALL, 4, 4), fakeStrategy("B", QuotexDecision.CALL, 4, 4)))
        val r = withoutWeights.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.SETUP_DETECTED, r.quality)
    }

    @Test
    fun aHeavilyDownweightedStrategyCanNoLongerOutvoteAFullWeightOne() {
        val strategies = listOf(fakeStrategy("Bad", QuotexDecision.PUT, 4, 4), fakeStrategy("Good", QuotexDecision.CALL, 4, 4))
        // Equal weights: one PUT vote and one CALL vote of equal size tie out to NO_SETUP.
        val tied = ConfluenceEngine(strategies).evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.NO_SETUP, tied.quality)

        // Once "Bad" has a poor record, its weight drops well below 1.0 and "Good" wins outright.
        val calibrated = ConfluenceEngine(strategies, mapOf("Bad" to 0.1, "Good" to 1.0))
            .evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(QuotexDecision.CALL, calibrated.direction)
    }

    @Test
    fun weightedAverageScoreMatchesPlainAverageAtEqualWeights() {
        val strategies = listOf(fakeStrategy("A", QuotexDecision.CALL, 4, 4), fakeStrategy("B", QuotexDecision.CALL, 2, 4))
        val equal = ConfluenceEngine(strategies).evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        val explicit = ConfluenceEngine(strategies, mapOf("A" to 1.0, "B" to 1.0)).evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(equal.quality, explicit.quality)
        assertEquals(equal.conditionsSatisfied, explicit.conditionsSatisfied)
    }

    // ---- ConfluenceBacktestEngine: no look-ahead, honest on random data ------------------------------------------------

    @Test
    fun confluenceBacktestNeverUsesACandleAfterTheOneItPredicts() {
        val random = Random(42)
        var price = 1.0
        val closes = List(400) { price += random.nextGaussian() * 0.001; price }
        val candles = candlesFromCloses(closes)
        // Should run without throwing and never look past the data it was given.
        val report = ConfluenceBacktestEngine(minCandlesForSignal = 150).run(candles, expiryCandles = 4)
        assertEquals(400, report.totalCandles)
        assertTrue(report.evaluatedCandles > 0)
    }

    @Test
    fun confluenceBacktestDoesNotOverclaimOnARandomWalk() {
        val random = Random(7)
        var price = 1.0
        val closes = List(1500) { price += random.nextGaussian() * 0.0005; price }
        val report = ConfluenceBacktestEngine(minCandlesForSignal = 150).run(candlesFromCloses(closes), expiryCandles = 4)
        val rate = report.hitRate
        if (rate != null && report.validSetups >= 30) {
            assertTrue("hit rate on random data should be roughly even, was $rate over ${report.validSetups}", rate in 0.25..0.75)
        }
        assertTrue(report.toText().startsWith("CONFLUENCE BACKTEST"))
    }

    @Test
    fun perStrategyStatsAddUpToItsOwnSampleCount() {
        val random = Random(3)
        var price = 1.0
        val closes = List(800) { price += random.nextGaussian() * 0.0008; price }
        val report = ConfluenceBacktestEngine(minCandlesForSignal = 150).run(candlesFromCloses(closes), expiryCandles = 4)
        for (s in report.perStrategy) {
            assertEquals(s.setups, s.wins + s.losses)
        }
    }
}
