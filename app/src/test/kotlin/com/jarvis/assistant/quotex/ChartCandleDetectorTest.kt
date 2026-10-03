package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.ocr.AxisLabel
import com.jarvis.assistant.quotex.ocr.ChartCandleDetector
import com.jarvis.assistant.quotex.ocr.PriceAxisCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartCandleDetectorTest {
    // price = 120 - 0.5 * y  (y = 20 -> 110, 60 -> 90, 100 -> 70, 140 -> 50, 180 -> 30)
    private val labels = listOf(20 to 110.0, 60 to 90.0, 100 to 70.0, 140 to 50.0, 180 to 30.0).map { AxisLabel(it.second, it.first.toFloat()) }
    private val W = 400
    private val H = 200
    private val GREEN = (0xFF shl 24) or (15 shl 16) or (175 shl 8) or 89
    private val RED = (0xFF shl 24) or (255 shl 16) or (98 shl 8) or 81

    private data class Spec(val o: Double, val h: Double, val l: Double, val c: Double)

    private fun y(price: Double) = ((120.0 - price) / 0.5).toInt()

    private fun draw(specs: List<Spec>, bodyW: Int = 7, step: Int = 14): IntArray {
        val px = IntArray(W * H) { 0xFF000000.toInt() }
        specs.forEachIndexed { i, s ->
            val x0 = 10 + i * step
            val colour = if (s.c >= s.o) GREEN else RED
            val cx = x0 + bodyW / 2
            for (yy in y(s.h) until y(s.l)) px[yy * W + cx] = colour
            val top = y(maxOf(s.o, s.c)); val bottom = y(minOf(s.o, s.c))
            for (yy in top until bottom) for (xx in x0 until x0 + bodyW) px[yy * W + xx] = colour
        }
        return px
    }

    private fun specs() = (0 until 12).map { i ->
        val base = 60.0 + (i % 5) * 4
        if (i % 2 == 0) Spec(base, base + 6, base - 3, base + 4) else Spec(base + 4, base + 7, base - 2, base)
    }

    @Test
    fun calibrationRecoversThePriceScale() {
        val cal = PriceAxisCalibration.fit(labels)!!
        assertEquals(110.0, cal.priceAt(20.0), 1e-6)
        assertEquals(70.0, cal.priceAt(100.0), 1e-6)
    }

    @Test
    fun calibrationRefusesLabelsThatAreNotOnALine() {
        // A curve: no three of these four points are collinear, so no subset can be trusted.
        val curved = listOf(110.0 to 20f, 95.0 to 60f, 75.0 to 100f, 50.0 to 140f).map { AxisLabel(it.first, it.second) }
        assertNull(PriceAxisCalibration.fit(curved))
    }

    @Test
    fun calibrationDropsASingleMisreadLabel() {
        val oneBad = labels.toMutableList().also { it[2] = AxisLabel(75.0, 100f) }
        assertEquals(110.0, PriceAxisCalibration.fit(oneBad)!!.priceAt(20.0), 1e-6)
    }

    @Test
    fun readsExactOhlcIncludingWicks() {
        val s = specs()
        val d = ChartCandleDetector().detect(draw(s), W, H, 300, PriceAxisCalibration.fit(labels), 1_000_000L, 60_000L)
        assertEquals(12, d.candles.size)
        assertTrue(d.confidence > 0.9)
        d.candles.forEachIndexed { i, c ->
            assertEquals(s[i].o, c.open, 1e-6); assertEquals(s[i].h, c.high, 1e-6)
            assertEquals(s[i].l, c.low, 1e-6); assertEquals(s[i].c, c.close, 1e-6)
        }
        assertEquals(1_000_000L, d.candles.last().openTimeMs)
        assertEquals(11, d.closed.size)
    }

    @Test
    fun refusesWithoutCalibration() {
        val d = ChartCandleDetector().detect(draw(specs()), W, H, 300, null, 0L, 60_000L)
        assertTrue(d.candles.isEmpty()); assertEquals(0.0, d.confidence, 0.0)
    }

    @Test
    fun refusesWhenTooFewCandles() {
        val d = ChartCandleDetector().detect(draw(specs().take(3)), W, H, 300, PriceAxisCalibration.fit(labels), 0L, 60_000L)
        assertTrue(d.candles.isEmpty())
    }

    @Test
    fun touchingCandlesOfAlternatingColourAreSeparatedByColour() {
        val s = specs() // alternating green / red, drawn touching (step == body width)
        val d = ChartCandleDetector().detect(draw(s, bodyW = 7, step = 7), W, H, 300, PriceAxisCalibration.fit(labels), 0L, 60_000L)
        assertEquals(12, d.candles.size)
        assertEquals(s[3].h, d.candles[3].high, 1e-6)
        assertEquals(s[3].o, d.candles[3].open, 1e-6)
    }

    @Test
    fun touchingSameColourCandlesAreSplitByWidth() {
        // 10 separated green candles; candles 4 and 5 touch (one 14px run that is two 7px candles).
        val s = (0 until 10).map { i -> val base = 60.0 + (i % 5) * 4; Spec(base, base + 6, base - 3, base + 3) }
        val px = IntArray(W * H) { 0xFF000000.toInt() }
        var x0 = 10
        s.forEachIndexed { i, sp ->
            val cx = x0 + 3
            for (yy in y(sp.h) until y(sp.l)) px[yy * W + cx] = GREEN
            for (yy in y(maxOf(sp.o, sp.c)) until y(minOf(sp.o, sp.c))) for (xx in x0 until x0 + 7) px[yy * W + xx] = GREEN
            x0 += if (i == 4) 7 else 14
        }
        val d = ChartCandleDetector().detect(px, W, H, 300, PriceAxisCalibration.fit(labels), 0L, 60_000L)
        assertEquals(10, d.candles.size)
        assertEquals(s[5].c, d.candles[5].close, 1e-6)
    }

    @Test
    fun identicalTouchingCandlesCannotBeSeparatedAndAreRefused() {
        val same = (0 until 12).map { Spec(70.0, 76.0, 66.0, 73.0) }
        val d = ChartCandleDetector().detect(draw(same, bodyW = 7, step = 7), W, H, 300, PriceAxisCalibration.fit(labels), 0L, 60_000L)
        assertTrue(d.candles.isEmpty())
    }

    @Test
    fun aMergedBlockOnTheLeftDoesNotSpoilTheCleanCandlesOnTheRight() {
        // 6 identical touching candles (cannot be split) on the left, 8 distinct separated candles on the right.
        val merged = (0 until 6).map { Spec(70.0, 76.0, 66.0, 73.0) }
        val clean = specs().take(8)
        val px = IntArray(W * H) { 0xFF000000.toInt() }
        fun paint(sp: List<Spec>, startX: Int, bodyW: Int, step: Int) {
            sp.forEachIndexed { i, s ->
                val x0 = startX + i * step
                val colour = if (s.c >= s.o) GREEN else RED
                val cx = x0 + bodyW / 2
                for (yy in y(s.h) until y(s.l)) px[yy * W + cx] = colour
                for (yy in y(maxOf(s.o, s.c)) until y(minOf(s.o, s.c))) for (xx in x0 until x0 + bodyW) px[yy * W + xx] = colour
            }
        }
        paint(merged, 10, 7, 7)
        paint(clean, 100, 7, 14)
        val d = ChartCandleDetector().detect(px, W, H, 300, PriceAxisCalibration.fit(labels), 5_000_000L, 60_000L)
        assertEquals(8, d.candles.size)
        assertEquals(5_000_000L, d.candles.last().openTimeMs)
        assertEquals(clean.last().c, d.candles.last().close, 1e-6)
    }

    @Test
    fun crossCheckAgainstOcrPrice() {
        val d = ChartCandleDetector().detect(draw(specs()), W, H, 300, PriceAxisCalibration.fit(labels), 0L, 60_000L)
        val last = d.candles.last().close
        assertTrue(ChartCandleDetector.agreesWith(d, last + 0.2, 0.5))
        assertFalse(ChartCandleDetector.agreesWith(d, last + 5.0, 0.5))
        assertNotNull(d)
    }
}
