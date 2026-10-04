package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraStrategiesTest {

    private fun ctx(candles: List<Candle>, regime: Regime): AnalysisContext = AnalysisContext(
        IndicatorSet(candles),
        RegimeResult(regime, Dir.WAIT, emptyList()),
        emptyList(),
        MtfResult(false, Dir.WAIT, Dir.WAIT, Dir.WAIT, 0.0, emptyList())
    )

    private fun alternating(from: Int, to: Int, amplitude: Double, start: MutableList<Candle>, mirror: Double = 1.0) {
        for (i in from until to) {
            val open = if (start.isEmpty()) 1.1 else start.last().close
            val close = 1.1 + (if (i % 2 == 0) amplitude else -amplitude) * mirror
            start.add(Candle(T0 + i * MIN_MS, open, maxOf(open, close) + 0.00001, minOf(open, close) - 0.00001, close))
        }
    }

    private fun squeezeThenBreakout(up: Boolean): List<Candle> {
        val c = ArrayList<Candle>()
        alternating(0, 60, 0.0004, c)
        alternating(60, 99, 0.00003, c)
        val open = c.last().close
        val close = if (up) 1.10015 else 1.09985
        val high = if (up) 1.10016 else maxOf(open, close) + 0.00001
        val low = if (up) minOf(open, close) - 0.00001 else 1.09984
        c.add(Candle(T0 + 99 * MIN_MS, open, high, low, close))
        return c
    }

    @Test
    fun newStrategiesAreRegisteredAndEnabledByDefault() {
        val ids = StrategyLibrary.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(StrategyLibrary.all.map { it.name }.size, StrategyLibrary.all.map { it.name }.toSet().size)
        for (id in listOf("squeeze", "vwap", "stoch_reversal")) {
            assertTrue(id in ids)
            assertTrue(id in DemoSettings().coerced().enabledStrategies)
        }
        assertEquals(StrategyLibrary.all.size, DEFAULT_STRATEGY_IDS.size)
    }

    @Test
    fun squeezeBreakoutFiresCallAfterATightSqueezeAndAnUpsideClose() {
        val v = BollingerSqueezeBreakout.evaluate(ctx(squeezeThenBreakout(true), Regime.RANGE))
        assertEquals(Dir.CALL, v.direction)
        assertTrue(v.score in 58..100)
    }

    @Test
    fun squeezeBreakoutFiresPutAfterATightSqueezeAndADownsideClose() {
        val v = BollingerSqueezeBreakout.evaluate(ctx(squeezeThenBreakout(false), Regime.RANGE))
        assertEquals(Dir.PUT, v.direction)
    }

    @Test
    fun squeezeBreakoutAbstainsWithoutASqueezeAndInAShock() {
        val noisy = ArrayList<Candle>()
        alternating(0, 99, 0.0004, noisy)
        noisy.add(Candle(T0 + 99 * MIN_MS, noisy.last().close, 1.1016, noisy.last().close - 0.00001, 1.1015))
        assertEquals(Dir.WAIT, BollingerSqueezeBreakout.evaluate(ctx(noisy, Regime.RANGE)).direction)
        assertEquals(Dir.WAIT, BollingerSqueezeBreakout.evaluate(ctx(squeezeThenBreakout(true), Regime.HIGH_VOLATILITY)).direction)
    }

    @Test
    fun vwapStrategyTradesTheTrendPullbackAndTheRangeStretch() {
        val flat = flatCandles(80)
        val bull = flat + Candle(T0 + 80 * MIN_MS, 1.1, 1.1001, 1.0999, 1.10005)
        assertEquals(Dir.CALL, VwapStrategy.evaluate(ctx(bull, Regime.TREND_UP)).direction)
        val bear = flat + Candle(T0 + 80 * MIN_MS, 1.1, 1.1001, 1.0999, 1.09995)
        assertEquals(Dir.PUT, VwapStrategy.evaluate(ctx(bear, Regime.TREND_DOWN)).direction)
        val stretchedUp = flat + Candle(T0 + 80 * MIN_MS, 1.1008, 1.1009, 1.0999, 1.1005)
        assertEquals(Dir.PUT, VwapStrategy.evaluate(ctx(stretchedUp, Regime.RANGE)).direction)
        // the same stretch is NOT faded while the market is trending
        assertEquals(Dir.WAIT, VwapStrategy.evaluate(ctx(stretchedUp, Regime.TREND_UP)).direction)
    }

    @Test
    fun stochasticReversalNeverTradesOutsideARange() {
        val candles = CandleSimulator.generate(Scenario.STRONG_CALL, 300)
        for (r in listOf(Regime.TREND_UP, Regime.TREND_DOWN, Regime.BREAKOUT, Regime.HIGH_VOLATILITY, Regime.UNCERTAIN)) {
            assertEquals(Dir.WAIT, StochasticReversal.evaluate(ctx(candles, r)).direction)
        }
    }

    @Test
    fun everyVoteStaysInRangeAcrossAllMarketsAndMeanReversionOnlyHappensInARange() {
        val s = testSettings()
        for (sc in Scenario.values()) {
            val candles = CandleSimulator.generate(sc, 420)
            for (end in 80..420 step 7) {
                val sub = candles.subList(0, end)
                val p = SignalPipeline.analyze(sub, s, sub.last().openTimeMs + MIN_MS, MIN_MS, checkFresh = false)
                for (v in p.votes) {
                    assertTrue(v.score in 0..100)
                    if (v.direction == Dir.WAIT) assertEquals(0, v.score)
                    if (v.strategy == StochasticReversal.name && v.direction != Dir.WAIT) {
                        assertEquals(Regime.RANGE, p.regime!!.regime)
                    }
                }
                assertEquals(StrategyLibrary.all.size, p.votes.size)
            }
        }
    }
}
