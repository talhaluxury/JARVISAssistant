package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.ocr.AxisLabel
import com.jarvis.assistant.quotex.ocr.ChartCandleDetector
import com.jarvis.assistant.quotex.ocr.PriceAxisCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartBannerTest {
    private val green = 0xFF22C55E.toInt()
    private val red = 0xFFEF4444.toInt()

    @Test
    fun greenBannerAndButtonsAreNotCandles() {
        val w = 200; val h = 240
        val px = IntArray(w * h) { 0xFF101820.toInt() }
        // Promo banner across the whole width (green) near the top.
        for (y in 5..20) for (x in 0 until w) px[y * w + x] = green
        // Eight candles, bodies y 100..140, wicks y 90..150.
        for (i in 0 until 8) {
            val x0 = 20 + 14 * i
            val c = if (i % 2 == 0) red else green
            for (y in 100..140) for (x in x0 until x0 + 8) px[y * w + x] = c
            for (y in 90..150) for (x in x0 + 3..x0 + 4) px[y * w + x] = c
        }
        // A big Buy button below the time axis.
        for (y in 200..230) for (x in 10..90) px[y * w + x] = green
        val cal = PriceAxisCalibration.fit(listOf(AxisLabel(1.2, 50f), AxisLabel(1.1, 100f), AxisLabel(1.0, 150f)), minLabels = 2)!!
        val det = ChartCandleDetector().detect(px, w, h, 180, cal, 1_000_000L, 10_000L, true, plotBottom = 190)
        assertEquals(8, det.candles.size)
        assertTrue("wick must not reach the banner", det.candles.all { it.high < 1.15 })
    }
}
