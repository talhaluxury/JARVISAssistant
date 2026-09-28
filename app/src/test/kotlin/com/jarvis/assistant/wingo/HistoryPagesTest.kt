package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.PeriodGap
import com.jarvis.assistant.wingo.domain.PeriodGaps
import com.jarvis.assistant.wingo.ocr.GameRegionDetector
import com.jarvis.assistant.wingo.ocr.OcrLine
import com.jarvis.assistant.wingo.ocr.ResultValidator
import com.jarvis.assistant.wingo.ocr.RowStatus
import com.jarvis.assistant.wingo.ocr.WinGoOCRParser
import com.jarvis.assistant.wingo.voice.WinGoNarrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagesTest {

    private fun p(seq: Int) = "2026092110005" + seq.toString().padStart(4, '0')

    // ---- gaps ---------------------------------------------------------------------------------------------

    @Test
    fun missingPeriodsBetweenStoredRoundsAreFound() {
        val gaps = PeriodGaps.find(listOf(p(1), p(2), p(5), p(6), p(10)))
        assertEquals(listOf(PeriodGap(p(3), p(4), 2), PeriodGap(p(7), p(9), 3)), gaps)
    }

    @Test
    fun aNewDayIsNotAGapAndCompleteHistoryHasNone() {
        assertTrue(PeriodGaps.find(listOf("20260920100052880", "20260921100050001")).isEmpty())
        assertTrue(PeriodGaps.find(listOf(p(1), p(2), p(3))).isEmpty())
        assertTrue(PeriodGaps.find(emptyList()).isEmpty())
    }

    @Test
    fun pageHintCountsTenRowsPerPageFromTheNewestRound() {
        assertEquals(1, PeriodGaps.pageHint(p(1299), p(1299)))
        assertEquals(1, PeriodGaps.pageHint(p(1299), p(1290)))
        assertEquals(2, PeriodGaps.pageHint(p(1299), p(1289)))
        assertEquals(3, PeriodGaps.pageHint(p(1299), p(1279)))
        assertNull(PeriodGaps.pageHint(p(1299), p(1300)))
    }

    // ---- the real 92dadu history layout ------------------------------------------------------------------------

    private fun word(text: String, left: Int, centerY: Int, width: Int, height: Int) =
        OcrLine(text, left, centerY - height / 2, left + width, centerY + height / 2, 0.95f)

    private val centers = listOf(962, 1077, 1193, 1308, 1423, 1538, 1653, 1769, 1884, 1999)
    private val numbers = listOf(7, 5, 7, 3, 1, 1, 7, 0, 9, 3)

    /** Ten rows like the screenshot (newest first) plus the "3/50" pager below the table. */
    private fun screenshotLines(): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        lines.add(word("Period", 190, 848, 110, 34))
        lines.add(word("Number", 495, 848, 130, 34))
        lines.add(word("Big", 695, 848, 50, 34))
        lines.add(word("Small", 750, 848, 90, 34))
        lines.add(word("Color", 915, 848, 90, 34))
        centers.forEachIndexed { i, cy ->
            lines.add(word(p(1166 - i), 95, cy, 300, 34))
            lines.add(word(numbers[i].toString(), 540, cy, 40, 62))
            lines.add(word(if (numbers[i] >= 5) "Big" else "Small", 725, cy, 90, 36))
        }
        lines.add(word("3/50", 505, 2225, 70, 34))
        return lines
    }

    @Test
    fun aFullHistoryPageIsReadValidatedAndItsPagerDetected() {
        val parser = WinGoOCRParser()
        val lines = screenshotLines()
        val rows = parser.parse(lines)
        assertEquals(10, rows.size)
        assertEquals(Pair(3, 50), parser.parsePager(lines))

        val verdicts = ResultValidator().validateBatch(rows, 1L)
        assertTrue(verdicts.all { it.status == RowStatus.TRUSTED })
        assertEquals(p(1166), verdicts.first().result!!.period)
        assertEquals(BigSmall.SMALL, verdicts[7].result!!.bigSmall) // the 0 row
        assertEquals(p(1157), verdicts.last().result!!.period)
    }

    @Test
    fun theCaptureAreaReachesDownToThePager() {
        val detection = GameRegionDetector().detect(screenshotLines(), 1080, 2436)
        assertTrue(detection.visible)
        val region = detection.historyRegion!!
        assertTrue("region must include the pager row", region.bottom * 2436f >= 2242f)
        assertTrue("region must include the first data row", region.top * 2436f <= 945f)
    }

    @Test
    fun pagerParsingIsStrict() {
        val parser = WinGoOCRParser()
        assertEquals(Pair(7, 50), parser.parsePager(listOf(word("07/50", 0, 0, 70, 30))))
        assertNull(parser.parsePager(listOf(word("51/50", 0, 0, 70, 30))))
        assertNull(parser.parsePager(listOf(word("abc", 0, 0, 70, 30))))
        assertNull(parser.parsePager(listOf(word(p(1166), 0, 0, 300, 30))))
    }

    // ---- wording ---------------------------------------------------------------------------------------------------

    @Test
    fun coverageTextNamesMissingRangesAndPages() {
        val state = WinGoUiState(
            historyCount = 120, missingRounds = 5, pageCurrent = 3, pageTotal = 50, backfilledSession = 30,
            gaps = listOf(GapInfo(p(1100), p(1104), 5, 3))
        )
        val text = WinGoNarrator.coverage(state)
        assertTrue(text.contains("Missing: 5 rounds"))
        assertTrue(text.contains("around page 3"))
        assertTrue(text.contains("page 3/50"))
        assertFalse(text.lowercase().contains("guaranteed"))
        assertNotNull(WinGoNarrator.coverage(WinGoUiState(historyCount = 120)))
        assertTrue(WinGoNarrator.coverage(WinGoUiState(historyCount = 120)).contains("No missing rounds"))
    }
}
