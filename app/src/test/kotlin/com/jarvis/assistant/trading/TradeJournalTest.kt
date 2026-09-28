package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test

class TradeJournalTest {

    private fun closedEntry(id: String, pnl: Double, order: Long) = TradeJournalEntry(
        id = id, timestampEpochMillis = order, pair = CurrencyPair.EURUSD,
        timeframesAnalyzed = listOf(Timeframe.H1), decision = TradeDecision.BUY_SETUP,
        confidenceScore = 80, checks = emptyList(),
        entry = 1.10, stopLoss = 1.095, takeProfit = 1.115, riskRewardRatio = 3.0,
        riskPercentOfEquity = 0.5, positionSizeLots = 0.1, reason = "test",
        executionStatus = ExecutionStatus.CLOSED, brokerOrderId = id, filledPrice = 1.10,
        exitPrice = 1.115, profitLoss = pnl, closedAtEpochMillis = order + 1000
    )

    @Test
    fun recordsAndRetrievesEntriesByPair() {
        val journal = InMemoryTradeJournal()
        val entry = closedEntry("e1", 10.0, 1000L)
        journal.record(entry)
        assertEquals(1, journal.all().size)
        assertEquals(1, journal.forPair(CurrencyPair.EURUSD).size)
        assertTrue(journal.forPair(CurrencyPair.GBPUSD).isEmpty())
    }

    @Test
    fun updateAppliesTransformAndReturnsUpdatedEntry() {
        val journal = InMemoryTradeJournal()
        journal.record(closedEntry("e1", 0.0, 1000L))
        val updated = journal.update("e1") { it.copy(profitLoss = 42.0) }
        assertNotNull(updated)
        assertEquals(42.0, updated!!.profitLoss!!, 1e-9)
        assertEquals(42.0, journal.all().first().profitLoss!!, 1e-9)
    }

    @Test
    fun updateReturnsNullForUnknownId() {
        val journal = InMemoryTradeJournal()
        assertNull(journal.update("missing") { it })
    }

    @Test
    fun analyticsComputesWinRateProfitFactorDrawdownAndStreaks() {
        // +100, +50, -30, -20, -10, +200 in chronological order
        val entries = listOf(
            closedEntry("t1", 100.0, 1000L),
            closedEntry("t2", 50.0, 2000L),
            closedEntry("t3", -30.0, 3000L),
            closedEntry("t4", -20.0, 4000L),
            closedEntry("t5", -10.0, 5000L),
            closedEntry("t6", 200.0, 6000L)
        )
        val analytics = entries.toAnalytics()

        assertEquals(6, analytics.totalClosedTrades)
        assertEquals(3, analytics.wins)
        assertEquals(3, analytics.losses)
        assertEquals(50.0, analytics.winRatePercent, 1e-9)
        assertEquals(350.0 / 3.0, analytics.averageWin, 1e-6)
        assertEquals(-20.0, analytics.averageLoss, 1e-9)
        assertEquals(350.0 / 60.0, analytics.profitFactor, 1e-6)
        assertEquals(60.0, analytics.maxDrawdown, 1e-9) // peak 150 after t2, trough 90 after t5
        assertEquals(3, analytics.maxConsecutiveLosses)
    }

    @Test
    fun analyticsHandlesNoClosedTradesGracefully() {
        val analytics = emptyList<TradeJournalEntry>().toAnalytics()
        assertEquals(0, analytics.totalClosedTrades)
        assertEquals(0.0, analytics.winRatePercent, 1e-9)
        assertEquals(0.0, analytics.profitFactor, 1e-9)
    }

    @Test
    fun analyticsIgnoresEntriesWithoutAClosedOutcome() {
        val open = TradeJournalEntry(
            id = "open1", timestampEpochMillis = 1L, pair = CurrencyPair.EURUSD,
            timeframesAnalyzed = emptyList(), decision = TradeDecision.BUY_SETUP, confidenceScore = 80,
            checks = emptyList(), entry = 1.1, stopLoss = 1.09, takeProfit = 1.12, riskRewardRatio = 2.0,
            riskPercentOfEquity = 0.5, positionSizeLots = 0.1, reason = "still open",
            executionStatus = ExecutionStatus.FILLED
        )
        val analytics = listOf(open, closedEntry("c1", 10.0, 2000L)).toAnalytics()
        assertEquals(1, analytics.totalClosedTrades)
    }
}
