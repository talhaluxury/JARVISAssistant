package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.agent.CandleResampler
import com.jarvis.assistant.quotex.analysis.CandleBuilder
import com.jarvis.assistant.quotex.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VwapActivityTest {

    private fun candlesWithTicks(n: Int, ticks: (Int) -> Int, price: (Int) -> Double = { 1.1 }): List<Candle> =
        List(n) { i ->
            val p = price(i)
            Candle(T0 + i * MIN_MS, p, p + 0.0001, p - 0.0001, p, ticks(i))
        }

    @Test
    fun candleBuilderCountsEveryReading() {
        val b = CandleBuilder(15)
        b.add(0L, 1.00); b.add(5_000L, 1.20); b.add(10_000L, 0.90)
        val first = b.add(15_000L, 1.10)
        assertEquals(3, first!!.ticks)
        // the reading that closed candle 1 is the first reading of candle 2
        b.add(20_000L, 1.11)
        assertEquals(2, b.add(30_000L, 1.12)!!.ticks)
    }

    @Test
    fun resampledCandlesSumTheirTicks() {
        val base = List(8) { Candle(it * 1000L, 1.0, 1.1, 0.9, 1.0, ticks = it + 1) }
        val out = CandleResampler.resample(base, 4, 1000L)
        assertEquals(2, out.size)
        assertEquals(1 + 2 + 3 + 4, out[0].ticks)
    }

    @Test
    fun withoutTickCountsEveryCandleWeighsTheSame() {
        val w = TA.activityWeights(candlesWithTicks(30, { 0 }))
        assertTrue(w.all { it == 1.0 })
    }

    @Test
    fun candlesWithoutATickCountGetTheMedianOfTheMeasuredOnes() {
        val c = candlesWithTicks(5, { if (it == 2) 0 else 10 + it })
        val w = TA.activityWeights(c)
        assertEquals(10.0, w[0], 1e-12)
        assertEquals(11.0, w[1], 1e-12)
        assertEquals(12.0, w[2], 1e-12) // median of 10, 11, 13, 14 = (11 + 13) / 2
        assertEquals(14.0, w[4], 1e-12)
    }

    @Test
    fun vwapEqualsTheTypicalPriceOnAFlatMarket() {
        val c = candlesWithTicks(80, { 5 })
        val ind = IndicatorSet(c)
        assertEquals(1.1, ind.at(ind.vwap), 1e-9)
        assertTrue(ind.vwap[58].isNaN()) // needs a full 60-candle window
        assertFalse(ind.vwap[59].isNaN())
    }

    @Test
    fun busyCandlesPullVwapTowardsTheirPrice() {
        val prices = { i: Int -> if (i == 70) 1.2 else 1.1 }
        val calm = IndicatorSet(candlesWithTicks(80, { 5 }, prices))
        val busy = IndicatorSet(candlesWithTicks(80, { if (it == 70) 500 else 5 }, prices))
        assertTrue(busy.at(busy.vwap) > calm.at(calm.vwap))
    }

    @Test
    fun activityRatioComparesTheNewestCandleWithThePrevious20() {
        val ind = IndicatorSet(candlesWithTicks(60, { if (it == 59) 30 else 10 }))
        assertTrue(ind.hasActivityData)
        assertEquals(3.0, ind.activityRatioNow(), 1e-9)
    }

    @Test
    fun activityIsUnavailableNotInventedWhenNoTicksWereMeasured() {
        val ind = IndicatorSet(CandleSimulator.generate(Scenario.MIXED, 120))
        assertFalse(ind.hasActivityData)
        assertTrue(ind.activityRatioNow().isNaN())
        assertNotNull(ind.snapshot()["vwap"])
        assertNull(ind.snapshot()["activity_ratio"])
    }

    @Test
    fun scorerWeightsStillAddUpToOneHundred() {
        val sum = SignalScorer.W_TREND + SignalScorer.W_MOMENTUM + SignalScorer.W_RSI + SignalScorer.W_MACD + SignalScorer.W_EMA +
            SignalScorer.W_VWAP + SignalScorer.W_SR + SignalScorer.W_CANDLE + SignalScorer.W_VOLATILITY + SignalScorer.W_SETUP +
            SignalScorer.W_MTF + SignalScorer.W_REGIME
        assertEquals(100.0, sum, 1e-9)
    }

    @Test
    fun theScoreBreakdownShowsTheVwapComponent() {
        val candles = CandleSimulator.generate(Scenario.STRONG_CALL, 300)
        val p = SignalPipeline.analyze(candles, testSettings(), candles.last().openTimeMs + MIN_MS, MIN_MS, checkFresh = false)
        val names = p.tech!!.components.map { it.name }
        assertTrue(names.contains("VWAP"))
    }
}
