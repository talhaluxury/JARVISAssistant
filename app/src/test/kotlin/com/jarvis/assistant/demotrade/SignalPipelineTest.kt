package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPipelineTest {
    private val s = testSettings()

    private fun signalAt(candles: List<com.jarvis.assistant.quotex.domain.Candle>, end: Int): TradeSignal {
        val sub = candles.subList(0, end)
        val now = sub.last().openTimeMs + MIN_MS
        val p = SignalPipeline.analyze(sub, s, now, MIN_MS, checkFresh = false)
        return SignalPipeline.finalize(p, null, s, "SIM", p.lastClose, now)
    }

    @Test
    fun highVolatilityShockIsRegimeHighVolatilityAndWait() {
        val candles = CandleSimulator.generate(Scenario.HIGH_VOLATILITY, 400)
        val shockStart = 400 - 150
        val sig = signalAt(candles, shockStart + 20)
        assertEquals(Regime.HIGH_VOLATILITY, sig.regime)
        assertEquals(Dir.WAIT, sig.direction)
        assertNotNull(sig.blockReason)
    }

    @Test
    fun tooLittleHistoryAlwaysWaits() {
        val candles = CandleSimulator.generate(Scenario.STRONG_CALL, 400)
        val sig = signalAt(candles, 30)
        assertEquals(Dir.WAIT, sig.direction)
        assertTrue(sig.confidence < s.weakThreshold)
    }

    @Test
    fun strongUptrendNeverProducesPut() {
        val candles = CandleSimulator.generate(Scenario.STRONG_CALL, 400)
        for (end in 80..400 step 5) assertTrue(signalAt(candles, end).direction != Dir.PUT)
    }

    @Test
    fun strongDowntrendNeverProducesCall() {
        val candles = CandleSimulator.generate(Scenario.STRONG_PUT, 400)
        for (end in 80..400 step 5) assertTrue(signalAt(candles, end).direction != Dir.CALL)
    }

    @Test
    fun confidenceStaysInRangeAndWaitNeverLooksTradeable() {
        for (sc in Scenario.values()) {
            val candles = CandleSimulator.generate(sc, 400)
            for (end in 70..400 step 10) {
                val sig = signalAt(candles, end)
                assertTrue(sig.confidence in 0..100)
                if (sig.direction == Dir.WAIT) assertTrue(sig.confidence < s.weakThreshold)
                if (sig.direction != Dir.WAIT) assertTrue(sig.confidence >= s.weakThreshold)
            }
        }
    }

    @Test
    fun signalOnlyDependsOnCandlesUpToItsEntry() {
        val candles = CandleSimulator.generate(Scenario.MIXED, 400)
        val a = signalAt(candles, 250)
        val b = signalAt(candles.subList(0, 250) + candles.subList(250, 400).map { it.copy(close = it.close, high = it.high + 1.0) }, 250)
        assertEquals(a.direction, b.direction)
        assertEquals(a.confidence, b.confidence)
    }

    @Test
    fun deterministicForSameInput() {
        val candles = CandleSimulator.generate(Scenario.MIXED, 400)
        val a = signalAt(candles, 300)
        val b = signalAt(candles, 300)
        assertEquals(a.direction, b.direction)
        assertEquals(a.confidence, b.confidence)
    }

    @Test
    fun ensembleNeedsAgreementAndTiesWait() {
        val calls = listOf(
            StrategyVote("A", Dir.CALL, 80, listOf("x")),
            StrategyVote("B", Dir.CALL, 70, listOf("x")),
            StrategyVote("C", Dir.WAIT, 0, listOf("x")),
            StrategyVote("D", Dir.CALL, 76, listOf("x"))
        )
        val r = StrategyEnsemble.combine(calls, 2)
        assertEquals(Dir.CALL, r.direction)
        assertEquals(3, r.agree)
        assertEquals("3/4", r.confirmationText)

        val tie = listOf(StrategyVote("A", Dir.CALL, 80, listOf()), StrategyVote("B", Dir.PUT, 80, listOf()))
        assertEquals(Dir.WAIT, StrategyEnsemble.combine(tie, 1).direction)

        val thin = listOf(StrategyVote("A", Dir.CALL, 90, listOf()), StrategyVote("B", Dir.WAIT, 0, listOf()))
        assertEquals(Dir.WAIT, StrategyEnsemble.combine(thin, 2).direction)

        val dissent = listOf(
            StrategyVote("A", Dir.CALL, 80, listOf()), StrategyVote("B", Dir.CALL, 80, listOf()),
            StrategyVote("C", Dir.PUT, 80, listOf()), StrategyVote("D", Dir.PUT, 80, listOf())
        )
        assertEquals(Dir.WAIT, StrategyEnsemble.combine(dissent, 2).direction)
    }

    @Test
    fun higherTimeframeDisagreementVetoes() {
        val mtf = MtfResult(true, Dir.CALL, Dir.WAIT, Dir.PUT, 0.0, emptyList())
        assertTrue(mtf.vetoes(Dir.CALL))
        assertFalse(mtf.vetoes(Dir.PUT))
        val missing = MtfResult(false, Dir.CALL, Dir.WAIT, Dir.WAIT, 0.0, emptyList())
        assertFalse(missing.vetoes(Dir.CALL)) // missing timeframe data is never invented
    }

    @Test
    fun resampleOnlyKeepsCompleteBuckets() {
        val candles = flatCandles(12)
        val r = MultiTimeframe.resample(candles, MIN_MS, 5)
        assertTrue(r.size <= 3)
        assertTrue(r.all { it.openTimeMs % (5 * MIN_MS) == 0L })
    }
}
