package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.ocr.OcrLine
import com.jarvis.assistant.wingo.ocr.ParsedRow
import com.jarvis.assistant.wingo.ocr.ResultStabilizer
import com.jarvis.assistant.wingo.ocr.ResultValidator
import com.jarvis.assistant.wingo.ocr.RowStatus
import com.jarvis.assistant.wingo.ocr.WinGoOCRParser
import com.jarvis.assistant.wingo.domain.RoundResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private fun period(seq: Int) = "2026092010005" + seq.toString().padStart(4, '0')

    private fun row(
        period: String?, number: Int?, label: BigSmall?, color: String? = null,
        confidence: Float = 0.95f, ambiguous: Boolean = false
    ) = ParsedRow(period, number, label, color, confidence, "raw", 0, 10, ambiguous)

    private val validator = ResultValidator()

    @Test
    fun acceptsAConsistentRow() {
        val v = validator.validateRow(row(period(1299), 7, BigSmall.BIG), 1L)
        assertEquals(RowStatus.TRUSTED, v.status)
        assertEquals(7, v.result!!.number)
    }

    @Test
    fun rejectsBigSmallLabelThatDisagreesWithNumber() {
        val v = validator.validateRow(row(period(1299), 7, BigSmall.SMALL), 1L)
        assertEquals(RowStatus.REJECTED, v.status)
        assertNull(v.result)
    }

    @Test
    fun rejectsMalformedPeriods() {
        assertEquals(RowStatus.REJECTED, validator.validateRow(row("2026092010005129", 7, BigSmall.BIG), 1L).status)
        assertEquals(RowStatus.REJECTED, validator.validateRow(row(null, 7, BigSmall.BIG), 1L).status)
        assertEquals(RowStatus.REJECTED, validator.validateRow(row("20261320100051299", 7, BigSmall.BIG), 1L).status)
    }

    @Test
    fun rejectsNumbersOutsideZeroToNine() {
        assertEquals(RowStatus.REJECTED, validator.validateRow(row(period(1), 10, BigSmall.BIG), 1L).status)
        assertEquals(RowStatus.REJECTED, validator.validateRow(row(period(1), null, BigSmall.BIG), 1L).status)
    }

    @Test
    fun missingLabelLowConfidenceAndAmbiguityAreUncertainNotTrusted() {
        assertEquals(RowStatus.UNCERTAIN, validator.validateRow(row(period(1), 7, null), 1L).status)
        assertEquals(RowStatus.UNCERTAIN, validator.validateRow(row(period(1), 7, BigSmall.BIG, confidence = 0.5f), 1L).status)
        assertEquals(RowStatus.UNCERTAIN, validator.validateRow(row(period(1), null, null, ambiguous = true), 1L).status)
    }

    @Test
    fun colourMustBeConsistentWithNumber() {
        assertEquals(RowStatus.REJECTED, validator.validateRow(row(period(1), 7, BigSmall.BIG, color = "RED"), 1L).status)
        // Only "red" was readable for a red+violet number: consistent, so still accepted.
        assertEquals(RowStatus.TRUSTED, validator.validateRow(row(period(1), 0, BigSmall.SMALL, color = "RED"), 1L).status)
    }

    @Test
    fun batchAcceptsConsecutivePeriods() {
        val rows = listOf(
            row(period(1299), 7, BigSmall.BIG),
            row(period(1298), 2, BigSmall.SMALL),
            row(period(1297), 0, BigSmall.SMALL)
        )
        val verdicts = validator.validateBatch(rows, 1L)
        assertTrue(verdicts.all { it.status == RowStatus.TRUSTED })
    }

    @Test
    fun batchFlagsARowThatBreaksThePeriodSequence() {
        val rows = listOf(
            row(period(1299), 7, BigSmall.BIG),
            row(period(1298), 2, BigSmall.SMALL),
            row(period(1200), 4, BigSmall.SMALL)
        )
        val verdicts = validator.validateBatch(rows, 1L)
        assertEquals(RowStatus.TRUSTED, verdicts[0].status)
        assertEquals(RowStatus.TRUSTED, verdicts[1].status)
        assertEquals(RowStatus.UNCERTAIN, verdicts[2].status)
    }

    @Test
    fun batchIgnoresDuplicatePeriods() {
        val rows = listOf(
            row(period(1299), 7, BigSmall.BIG),
            row(period(1299), 7, BigSmall.BIG),
            row(period(1298), 2, BigSmall.SMALL)
        )
        val verdicts = validator.validateBatch(rows, 1L)
        assertEquals(2, verdicts.count { it.status == RowStatus.TRUSTED })
        assertEquals(1, verdicts.count { it.status == RowStatus.REJECTED })
    }

    @Test
    fun batchMarksConflictingDuplicatesUncertain() {
        val rows = listOf(
            row(period(1299), 7, BigSmall.BIG),
            row(period(1299), 2, BigSmall.SMALL)
        )
        val verdicts = validator.validateBatch(rows, 1L)
        assertTrue(verdicts.all { it.status == RowStatus.UNCERTAIN })
    }

    // ---- parser ----------------------------------------------------------------------------------

    private fun word(text: String, left: Int, top: Int) = OcrLine(text, left, top, left + text.length * 12, top + 30, 0.95f)

    @Test
    fun parserReadsHistoryRowsTopToBottomAndIgnoresHeaders() {
        val lines = listOf(
            word("Period", 10, 40), word("Number", 300, 40), word("Big/Small", 450, 40),
            word(period(1299), 10, 100), word("7", 320, 96), word("Big", 450, 100),
            word(period(1298), 10, 160), word("2", 320, 156), word("Small", 450, 160)
        )
        val rows = WinGoOCRParser().parse(lines)
        assertEquals(2, rows.size)
        assertEquals(period(1299), rows[0].period)
        assertEquals(7, rows[0].number)
        assertEquals(BigSmall.BIG, rows[0].bigSmall)
        assertEquals(period(1298), rows[1].period)
        assertEquals(2, rows[1].number)
        assertEquals(BigSmall.SMALL, rows[1].bigSmall)
        assertFalse(rows[0].ambiguous)
    }

    @Test
    fun parserFlagsTwoCandidateDigitsAsAmbiguous() {
        val lines = listOf(word(period(1299), 10, 100), word("3", 320, 100), word("4", 360, 100), word("Small", 450, 100))
        val row = WinGoOCRParser().parse(lines).single()
        assertTrue(row.ambiguous)
        assertNull(row.number)
    }

    // ---- stabiliser ------------------------------------------------------------------------------

    private fun result(seq: Int, number: Int) = RoundResult(period(seq), number, 1L)

    @Test
    fun resultNeedsTwoIdenticalReadingsToBeConfirmed() {
        val s = ResultStabilizer(2)
        assertTrue(s.offer(listOf(result(1299, 7))).isEmpty())
        assertTrue(s.hasPending())
        val confirmed = s.offer(listOf(result(1299, 7)))
        assertEquals(1, confirmed.size)
        assertFalse(s.hasPending())
        assertTrue(s.offer(listOf(result(1299, 7))).isEmpty()) // already confirmed, not emitted again
    }

    @Test
    fun conflictingReadingsRestartConfirmation() {
        val s = ResultStabilizer(2)
        s.offer(listOf(result(1299, 7)))
        assertTrue(s.offer(listOf(result(1299, 8))).isEmpty())
        val confirmed = s.offer(listOf(result(1299, 8)))
        assertEquals(8, confirmed.single().number)
    }

    @Test
    fun readingsThatNeverRepeatAreDropped() {
        val s = ResultStabilizer(2)
        s.offer(listOf(result(1299, 7)))
        s.offer(emptyList())
        s.offer(emptyList())
        s.offer(emptyList())
        assertFalse(s.hasPending())
        assertNotNull(s)
    }
}
