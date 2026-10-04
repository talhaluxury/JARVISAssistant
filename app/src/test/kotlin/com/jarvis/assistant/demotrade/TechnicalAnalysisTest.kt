package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalAnalysisTest {

    @Test
    fun smaMatchesHandCalculation() {
        val out = TA.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        assertEquals(2.0, out[2], 1e-9)
        assertEquals(3.0, out[3], 1e-9)
        assertEquals(4.0, out[4], 1e-9)
    }

    @Test
    fun emaIsSeededWithSmaAndUndefinedBefore() {
        val out = TA.ema(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(out[1].isNaN())
        assertEquals(2.0, out[2], 1e-9)
        assertEquals(3.0, out[3], 1e-9) // 4*0.5 + 2*0.5
        assertEquals(4.0, out[4], 1e-9)
    }

    @Test
    fun rsiExtremesAndFlat() {
        val up = DoubleArray(40) { 1.0 + it * 0.01 }
        assertEquals(100.0, TA.rsi(up, 14)[39], 1e-9)
        val down = DoubleArray(40) { 2.0 - it * 0.01 }
        assertEquals(0.0, TA.rsi(down, 14)[39], 1e-9)
        val flat = DoubleArray(40) { 1.0 }
        assertEquals(50.0, TA.rsi(flat, 14)[39], 1e-9)
    }

    @Test
    fun bollingerBandsCollapseOnConstantSeries() {
        val b = TA.bollinger(DoubleArray(30) { 1.5 }, 20, 2.0)
        assertEquals(1.5, b.mid[29], 1e-12)
        assertEquals(b.upper[29], b.lower[29], 1e-12)
    }

    @Test
    fun indicatorsNeverUseFutureData() {
        val candles = CandleSimulator.generate(Scenario.MIXED, 300)
        val full = IndicatorSet(candles)
        val prefix = IndicatorSet(candles.subList(0, 200))
        assertEquals(prefix.ema21[199], full.ema21[199], 1e-12)
        assertEquals(prefix.rsi14[199], full.rsi14[199], 1e-12)
        assertEquals(prefix.macd.hist[199], full.macd.hist[199], 1e-12)
        assertEquals(prefix.atr14[199], full.atr14[199], 1e-12)
        assertEquals(prefix.adx14.adx[199], full.adx14.adx[199], 1e-12)
    }

    @Test
    fun swingsNeedConfirmationOnBothSides() {
        val candles = CandleSimulator.generate(Scenario.SIDEWAYS, 200)
        val ind = IndicatorSet(candles)
        assertTrue(ind.swings.all { it.index >= 3 && it.index <= candles.size - 4 })
    }

    @Test
    fun adxAndAtrArePositiveOnTrendingData() {
        val ind = IndicatorSet(CandleSimulator.generate(Scenario.STRONG_CALL, 200))
        assertTrue(ind.atrNow > 0.0)
        assertTrue(ind.at(ind.adx14.adx) >= 0.0)
        assertTrue(ind.at(ind.adx14.adx) <= 100.0)
    }
}
