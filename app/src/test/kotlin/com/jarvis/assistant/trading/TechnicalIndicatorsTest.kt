package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test

class TechnicalIndicatorsTest {

    private fun candle(open: Double, high: Double, low: Double, close: Double, volume: Double? = null, index: Long = 0) =
        Candle(CurrencyPair.EURUSD, Timeframe.M15, index, open, high, low, close, volume, DataSource.DEMO_SIMULATED)

    @Test
    fun smaMatchesHandComputedValues() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val result = TechnicalIndicators.sma(values, 3)
        assertNull(result[0]); assertNull(result[1])
        assertEquals(2.0, result[2]!!, 1e-9)
        assertEquals(3.0, result[3]!!, 1e-9)
        assertEquals(4.0, result[4]!!, 1e-9)
    }

    @Test
    fun emaSeedsFromSmaThenRecurses() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val result = TechnicalIndicators.ema(values, 3)
        // seed = avg(1,2,3) = 2.0 at index 2
        assertEquals(2.0, result[2]!!, 1e-9)
        // next = (4 - 2) * 0.5 + 2 = 3.0
        assertEquals(3.0, result[3]!!, 1e-9)
    }

    @Test
    fun rsiIsHighInSustainedUptrendAndLowInDowntrend() {
        val uptrend = (1..30).map { it.toDouble() }
        val upRsi = TechnicalIndicators.rsi(uptrend, 14).filterNotNull()
        assertTrue("expected RSI > 90 in pure uptrend, was ${upRsi.last()}", upRsi.last() > 90.0)

        val downtrend = (1..30).map { (31 - it).toDouble() }
        val downRsi = TechnicalIndicators.rsi(downtrend, 14).filterNotNull()
        assertTrue("expected RSI < 10 in pure downtrend, was ${downRsi.last()}", downRsi.last() < 10.0)
    }

    @Test
    fun rsiStaysWithinBounds() {
        val noisy = listOf(1.0, 2.0, 1.5, 3.0, 2.8, 4.0, 3.5, 5.0, 4.8, 6.0, 5.5, 7.0, 6.8, 8.0, 7.5, 9.0)
        TechnicalIndicators.rsi(noisy, 14).filterNotNull().forEach {
            assertTrue(it in 0.0..100.0)
        }
    }

    @Test
    fun macdHistogramPositiveInUptrend() {
        val uptrend = (1..60).map { it.toDouble() }
        val macd = TechnicalIndicators.macd(uptrend)
        val lastHistogram = macd.histogram.filterNotNull().last()
        assertTrue("expected positive histogram (fast EMA above slow) in uptrend, was $lastHistogram", lastHistogram > 0.0)
    }

    @Test
    fun atrIsPositiveForVolatileCandles() {
        val candles = listOf(
            candle(1.0, 1.05, 0.95, 1.02, index = 0),
            candle(1.02, 1.10, 0.98, 1.05, index = 1),
            candle(1.05, 1.12, 1.00, 1.08, index = 2),
            candle(1.08, 1.15, 1.02, 1.10, index = 3),
            candle(1.10, 1.18, 1.05, 1.14, index = 4)
        )
        val atr = TechnicalIndicators.atr(candles, period = 3)
        assertNotNull(atr[3])
        assertTrue(atr[3]!! > 0.0)
    }

    @Test
    fun adxProducesBoundedValuesAfterWarmup() {
        val candles = (0 until 40).map { i ->
            val base = 1.0 + i * 0.01 // steady trend -> should yield meaningfully high ADX
            candle(base, base + 0.02, base - 0.005, base + 0.015, index = i.toLong())
        }
        val adx = TechnicalIndicators.adx(candles, period = 14).filterNotNull()
        assertTrue(adx.isNotEmpty())
        adx.forEach { assertTrue(it in 0.0..100.0) }
        assertTrue("expected elevated ADX in a steady one-directional move, was ${adx.last()}", adx.last() > 20.0)
    }

    @Test
    fun bollingerBandsOrderCorrectly() {
        val values = listOf(1.0, 1.1, 0.9, 1.2, 0.8, 1.3, 0.7, 1.1, 0.95, 1.05, 1.0, 1.02, 0.98, 1.15, 0.85, 1.2, 0.8, 1.1, 0.9, 1.0)
        val bands = TechnicalIndicators.bollingerBands(values, period = 10)
        for (i in values.indices) {
            val mid = bands.middle[i]; val up = bands.upper[i]; val low = bands.lower[i]
            if (mid != null) {
                assertTrue(up!! >= mid)
                assertTrue(low!! <= mid)
            }
        }
    }

    @Test
    fun stochasticStaysWithinZeroToHundred() {
        val candles = (0 until 30).map { i ->
            val base = 1.0 + (i % 5) * 0.01
            candle(base, base + 0.015, base - 0.01, base + 0.005, index = i.toLong())
        }
        val stoch = TechnicalIndicators.stochastic(candles)
        stoch.k.filterNotNull().forEach { assertTrue(it in 0.0..100.0) }
        stoch.d.filterNotNull().forEach { assertTrue(it in 0.0..100.0) }
    }

    @Test
    fun vwapReturnsAllNullWithoutVolume() {
        val candles = listOf(candle(1.0, 1.1, 0.9, 1.05), candle(1.05, 1.12, 0.98, 1.08))
        assertTrue(TechnicalIndicators.vwap(candles).all { it == null })
    }

    @Test
    fun vwapComputesVolumeWeightedAverageWhenVolumePresent() {
        val candles = listOf(
            candle(1.0, 1.02, 0.98, 1.0, volume = 100.0),
            candle(1.0, 1.06, 0.99, 1.05, volume = 300.0)
        )
        val vwap = TechnicalIndicators.vwap(candles)
        assertNotNull(vwap[0])
        assertNotNull(vwap[1])
        // second VWAP should be pulled toward the heavier-volume second candle's typical price
        val typicalPrice2 = (1.06 + 0.99 + 1.05) / 3.0
        assertTrue(vwap[1]!! > vwap[0]!!)
        assertTrue(abs(vwap[1]!! - typicalPrice2) < abs(vwap[0]!! - typicalPrice2))
    }

    private fun abs(d: Double) = kotlin.math.abs(d)
}
