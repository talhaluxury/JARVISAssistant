package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.data.JournalEntry
import com.jarvis.assistant.quotex.voice.QuotexNarrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {

    private fun entry(
        id: Long, direction: String = "CALL", correct: Boolean? = null, asset: String = "EURUSD_OTC",
        confidence: Double = 0.62, quality: String = "SETUP_DETECTED"
    ) = JournalEntry(
        id = id, timestamp = id, asset = asset, candleSeconds = 15, expiryCandles = 4, direction = direction,
        confidence = confidence, agree = 3, totalModels = 9, trend = "STRONG_UP", volatility = "NORMAL",
        confluenceQuality = quality, signalState = "CONFIRMED_SETUP", entryPrice = 1.08, reasonSummary = "3/9 models agree",
        resolvedAt = if (correct != null) id + 1 else null, actualDirection = if (correct != null) direction else null, correct = correct
    )

    @Test
    fun emptyJournalSaysNothingLoggedYet() {
        assertTrue(QuotexNarrator.journalSummary(emptyList(), "Today").contains("no signals logged"))
    }

    @Test
    fun allPendingEntriesAreReportedAsNotYetResolved() {
        val text = QuotexNarrator.journalSummary(listOf(entry(1, correct = null), entry(2, correct = null)), "Today")
        assertTrue(text.contains("none resolved yet"))
    }

    @Test
    fun resolvedEntriesReportAWinLossCountAndPendingCount() {
        val entries = listOf(entry(1, correct = true), entry(2, correct = false), entry(3, correct = null))
        val text = QuotexNarrator.journalSummary(entries, "Last 3 setups")
        assertTrue(text.contains("1/2 correct"))
        assertTrue(text.contains("1 still pending"))
    }

    @Test
    fun summaryListsEachEntryWithItsOutcomeMarker() {
        val text = QuotexNarrator.journalSummary(listOf(entry(1, correct = true), entry(2, correct = false)), "Today")
        assertTrue(text.contains("✓"))
        assertTrue(text.contains("✕"))
        assertTrue(text.contains("CALL"))
        assertTrue(text.contains("EURUSD_OTC"))
    }

    @Test
    fun explainFailureWithNoRecordIsHonestAboutHavingNothing() {
        assertTrue(QuotexNarrator.explainFailure(null).contains("No resolved losing signal"))
    }

    @Test
    fun explainFailureQuotesTheFrozenReasoningAndNeverOverclaims() {
        val e = entry(1, direction = "PUT", correct = false, quality = "HIGH_CONFLUENCE_SETUP")
        val text = QuotexNarrator.explainFailure(e)
        assertTrue(text.contains("PUT"))
        assertTrue(text.contains("HIGH_CONFLUENCE_SETUP"))
        assertTrue(text.contains("3/9 models agree")) // the original reasoning, verbatim
        assertFalse(text.lowercase().contains("guaranteed"))
        assertFalse(text.lowercase().contains("will not happen again"))
    }
}
