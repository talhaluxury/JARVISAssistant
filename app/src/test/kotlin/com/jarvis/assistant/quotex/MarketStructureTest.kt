package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.Indicators
import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.quotex.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketStructureTest {

    private fun candle(i: Int, o: Double, h: Double, l: Double, c: Double) = Candle(i * 15_000L, o, h, l, c)

    // ---- new indicators ------------------------------------------------------------------------------

    @Test
    fun atrIsZeroOnAFlatSeriesAndPositiveOnARangingOne() {
        val flatHighs = DoubleArray(40) { 1.0 }
        val flatLows = DoubleArray(40) { 1.0 }
        val flatCloses = DoubleArray(40) { 1.0 }
        val flatAtr = Indicators.atr(flatHighs, flatLows, flatCloses, 14)
        assertTrue(flatAtr[14].isNaN().not())
        assertEquals(0.0, flatAtr[30], 1e-9)

        val highs = DoubleArray(40) { 1.0 + (it % 2) * 0.5 }
        val lows = DoubleArray(40) { 1.0 - (it % 2) * 0.5 }
        val closes = DoubleArray(40) { 1.0 }
        val atr = Indicators.atr(highs, lows, closes, 14)
        // true range alternates 0 / 1.0, so Wilder's ATR oscillates around 0.5 (about 0.48 right after a 0 candle)
        assertTrue(atr[30] > 0.4)
    }

    @Test
    fun macdHistogramIsPositiveDuringASustainedUptrend() {
        val closes = DoubleArray(80) { 1.0 + it * 0.01 }
        val (_, _, histogram) = Indicators.macd(closes)
        assertTrue(histogram[79] > 0.0)
    }

    @Test
    fun stochasticReadsNearTheTopWhenCloseIsAtTheRecentHigh() {
        val highs = DoubleArray(30) { 1.0 + it * 0.01 }
        val lows = DoubleArray(30) { 0.9 + it * 0.01 }
        val closes = DoubleArray(30) { highs[it] } // close = high every candle
        val (k, _) = Indicators.stochastic(highs, lows, closes, period = 14)
        assertTrue(k[29] > 95.0)
    }

    @Test
    fun adxIsHighOnAOneDirectionalMoveAndLowOnAFlatMarket() {
        val n = 100
        val trendingHighs = DoubleArray(n) { 1.0 + it * 0.01 }
        val trendingLows = DoubleArray(n) { 0.95 + it * 0.01 }
        val trendingCloses = DoubleArray(n) { 0.98 + it * 0.01 }
        val trendingAdx = Indicators.adx(trendingHighs, trendingLows, trendingCloses, 14)
        assertTrue(trendingAdx[n - 1] > 25.0)

        val flatHighs = DoubleArray(n) { 1.0 + (it % 2) * 0.001 }
        val flatLows = DoubleArray(n) { 1.0 - (it % 2) * 0.001 }
        val flatCloses = DoubleArray(n) { 1.0 }
        val flatAdx = Indicators.adx(flatHighs, flatLows, flatCloses, 14)
        assertTrue(flatAdx[n - 1] < 20.0)
    }

    @Test
    fun rawBollingerBandsBracketTheMovingAverage() {
        val closes = DoubleArray(30) { 1.0 + (it % 5) * 0.01 }
        val (lower, mid, upper) = Indicators.bollingerBands(closes, 20, 2.0)
        assertTrue(lower[25] < mid[25])
        assertTrue(mid[25] < upper[25])
    }

    // ---- market structure ------------------------------------------------------------------------------------

    @Test
    fun swingHighsAndLowsAreFoundInAZigZag() {
        // 0 up to a peak at index 5, back down to a trough at index 10, up again - clear zig-zag.
        val candles = (0..20).map {
            val price = when {
                it <= 5 -> 1.0 + it * 0.01
                it <= 10 -> 1.05 - (it - 5) * 0.01
                else -> 1.0 + (it - 10) * 0.01
            }
            candle(it, price, price + 0.001, price - 0.001, price)
        }
        val swings = MarketStructure.swings(PriceSeries(candles), lookback = 3)
        assertTrue(swings.any { it.isHigh && it.index == 5 })
        assertTrue(swings.any { !it.isHigh && it.index == 10 })
    }

    @Test
    fun structureLabelComparesTheLastTwoSwingsOfTheSameKind() {
        val higherHighs = listOf(
            com.jarvis.assistant.quotex.analysis.Swing(1, 1.0, true),
            com.jarvis.assistant.quotex.analysis.Swing(5, 1.2, true)
        )
        assertEquals(com.jarvis.assistant.quotex.analysis.StructureLabel.HIGHER_HIGH, MarketStructure.structureLabel(higherHighs))
        val lowerLows = listOf(
            com.jarvis.assistant.quotex.analysis.Swing(1, 1.0, false),
            com.jarvis.assistant.quotex.analysis.Swing(5, 0.8, false)
        )
        assertEquals(com.jarvis.assistant.quotex.analysis.StructureLabel.LOWER_LOW, MarketStructure.structureLabel(lowerLows))
        assertEquals(com.jarvis.assistant.quotex.analysis.StructureLabel.UNCLEAR, MarketStructure.structureLabel(emptyList()))
    }

    @Test
    fun trendLabelIsStrongUpOnASustainedRiseAndRangeOnAFlatMarket() {
        val n = 120
        val trending = (0 until n).map {
            val p = 1.0 + it * 0.01
            candle(it, p, p + 0.002, p - 0.002, p)
        }
        assertEquals(TrendState.STRONG_UP, MarketStructure.trendLabel(PriceSeries(trending)))

        val flat = (0 until n).map { candle(it, 1.0, 1.0005, 0.9995, 1.0) }
        val flatTrend = MarketStructure.trendLabel(PriceSeries(flat))
        assertTrue(flatTrend == TrendState.RANGE || flatTrend == TrendState.UNSTABLE)
    }

    @Test
    fun volatilityLabelIsHigherWhenRecentRangeExpands() {
        val n = 150
        val calm = (0 until n - 20).map { candle(it, 1.0, 1.0005, 0.9995, 1.0) }
        val wild = (n - 20 until n).map { candle(it, 1.0, 1.05, 0.95, 1.0) }
        val series = PriceSeries(calm + wild)
        val label = MarketStructure.volatilityLabel(series)
        assertTrue(label == VolatilityState.HIGH || label == VolatilityState.EXTREME)

        val steady = (0 until n).map { candle(it, 1.0, 1.001, 0.999, 1.0) }
        assertEquals(VolatilityState.NORMAL, MarketStructure.volatilityLabel(PriceSeries(steady)))
    }
}
