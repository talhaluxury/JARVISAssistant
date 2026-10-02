package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.ocr.AxisLabel
import com.jarvis.assistant.quotex.ocr.GridPriceFinder
import com.jarvis.assistant.quotex.ocr.QuotexScreenParser
import com.jarvis.assistant.wingo.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotexReaderTest {

    private fun word(text: String, left: Int, centerY: Int) = OcrLine(text, left, centerY - 15, left + 100, centerY + 15, 0.95f)

    private fun gridLines(live: String, liveY: Int): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        listOf("1.08200", "1.08220", "1.08240", "1.08260", "1.08280").forEachIndexed { i, t -> lines.add(word(t, 900, 500 - i * 100)) }
        lines.add(word(live, 900, liveY))
        return lines
    }

    @Test
    fun fourAxisNumbersAreEnoughWhenExactlyOneIsOffTheGrid() {
        val lines = listOf(
            word("EUR/USD", 20, 45),
            word("1.0820", 900, 500), word("1.0822", 900, 400), word("1.0824", 900, 300), word("1.08233", 900, 335)
        )
        val reading = QuotexScreenParser().parse(lines, 1080, 1600)
        assertEquals(1.08233, reading.price!!, 1e-12)
    }

    @Test
    fun fourGridLabelsAndNoLiveLabelGiveNoPrice() {
        val labels = listOf(1.0820, 1.0822, 1.0824, 1.0826).mapIndexed { i, v -> AxisLabel(v, 500f - i * 100f) }
        assertNull(GridPriceFinder.findLeaveOneOut(labels, 40f))
    }

    @Test
    fun liveLabelAtTheWrongHeightIsRejectedByTheRelaxedFinderToo() {
        val labels = listOf(
            AxisLabel(1.0820, 500f), AxisLabel(1.0822, 400f), AxisLabel(1.0824, 300f), AxisLabel(1.08233, 450f)
        )
        assertNull(GridPriceFinder.findLeaveOneOut(labels, 40f))
    }

    @Test
    fun priceTokensWithStrayMarksStillRead() {
        val lines = gridLines("1.08253\u25B6", 235) + word("EUR/USD", 20, 45)
        assertEquals(1.08253, QuotexScreenParser().parse(lines, 1080, 1600).price!!, 1e-12)
    }

    @Test
    fun assetWithoutSlashIsStillRead() {
        val lines = listOf(word("EURUSD", 20, 45), word("OTC", 300, 45))
        assertEquals("EURUSD_OTC", QuotexScreenParser().parse(lines, 1080, 1600).asset)
    }

    @Test
    fun nonPairAssetNameIsTakenFromTheRowBesideOtc() {
        val lines = listOf(word("Apple", 20, 45), word("(OTC)", 300, 45))
        assertEquals("APPLE_OTC", QuotexScreenParser().parse(lines, 1080, 1600).asset)
    }

    @Test
    fun tooFewAxisNumbersExplainsWhyNoPriceWasRead() {
        val lines = listOf(word("EUR/USD", 20, 45), word("1.0820", 900, 500), word("1.0822", 900, 400))
        val reading = QuotexScreenParser().parse(lines, 1080, 1600)
        assertNull(reading.price)
        assertTrue(reading.note.contains("Only 2"))
        assertEquals("EURUSD", reading.asset)
    }
}
