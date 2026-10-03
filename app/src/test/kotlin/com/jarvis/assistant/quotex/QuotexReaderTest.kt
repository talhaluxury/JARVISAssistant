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

class QuotexPhoneLayoutTest {
    private fun word(text: String, left: Int, centerY: Int) = OcrLine(text, left, centerY - 18, left + 130, centerY + 18, 0.95f)

    /** Layout of the Quotex phone app: asset in the bottom panel, payout amount on the right edge. */
    private fun phoneScreen(): List<OcrLine> = listOf(
        word("1.12500", 900, 290), word("1.12480", 900, 581), word("1.12460", 900, 870),
        word("1.12440", 900, 1161), word("1.12420", 900, 1452), word("1.12417", 900, 1530),
        word("1.12413", 470, 1628),              // crosshair label, not on the right-hand axis
        word("EUR/USD", 130, 1800), word("88%", 300, 1800),
        word("3.76", 930, 2022), word("$", 1040, 2022) // payout amount: a number, but not a price
    )

    @Test
    fun assetInTheBottomPanelIsFound() {
        assertEquals("EURUSD", QuotexScreenParser().parse(phoneScreen(), 1080, 2000).asset)
    }

    @Test
    fun payoutAmountDoesNotBreakThePriceRead() {
        val reading = QuotexScreenParser().parse(phoneScreen(), 1080, 2000)
        assertEquals(1.12417, reading.price!!, 1e-12)
    }
}

class QuotexSelfScreenTest {
    private fun word(text: String, left: Int, centerY: Int) = OcrLine(text, left, centerY - 18, left + 130, centerY + 18, 0.95f)

    @Test
    fun jarvisOwnScreenIsNeverReadAsAChart() {
        val lines = listOf(
            word("AGENT", 40, 260), word("STATUS", 250, 260),
            word("Asset", 40, 430), word("ASSET_OTC", 800, 430),
            word("1.12500", 900, 600), word("1.12480", 900, 700), word("1.12460", 900, 800), word("1.12440", 900, 900)
        )
        val reading = QuotexScreenParser().parse(lines, 1080, 2000)
        assertNull(reading.price)
        assertNull(reading.asset)
        assertTrue(reading.note.contains("own screen"))
    }

    @Test
    fun otcOnAnotherRowDoesNotTurnARegularPairIntoOtc() {
        val lines = listOf(word("EUR/USD", 130, 1800), word("88%", 300, 1800), word("OTC", 100, 300))
        assertEquals("EURUSD", QuotexScreenParser().parse(lines, 1080, 2000).asset)
    }

    @Test
    fun uiWordsAreNotMistakenForAnAssetName() {
        val lines = listOf(word("Asset", 20, 45), word("(OTC)", 300, 45))
        assertNull(QuotexScreenParser().parse(lines, 1080, 2000).asset)
    }
}

class RoundGridPhoneTest {
    @org.junit.Test
    fun threeAxisNumbersGiveLivePrice() {
        // Phone chart: two grid lines (1.12120, 1.12140) + live chip 1.12162 between/above them.
        val labels = listOf(
            AxisLabel(1.12140, 490f), AxisLabel(1.12120, 742f), AxisLabel(1.12162, 240f)
        )
        org.junit.Assert.assertEquals(1.12162, GridPriceFinder.findRoundGrid(labels, 40f)!!, 1e-9)
    }

    @org.junit.Test
    fun ambiguousThreeNumbersGiveNull() {
        val labels = listOf(AxisLabel(1.12140, 490f), AxisLabel(1.12120, 742f), AxisLabel(1.12160, 240f))
        org.junit.Assert.assertNull(GridPriceFinder.findRoundGrid(labels, 40f))
    }
}
