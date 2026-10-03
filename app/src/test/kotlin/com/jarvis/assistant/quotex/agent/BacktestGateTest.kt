package com.jarvis.assistant.quotex.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestGateTest {
    private fun seg(name: String, wins: Int, losses: Int) = SegmentReport(
        name, wins + losses, wins, losses, 0, null, null, 0, 0, emptyMap(), emptyMap(), emptyMap(), emptyMap()
    )

    private fun report(oosWins: Int, oosLosses: Int) = AgentBacktestReport(
        candlesTested = 1000, evaluations = 300, waitCount = 0, noTradeCount = 0, dataUncertainCount = 0, watchCount = 0,
        breakEven = 0.54, train = seg("train", 90, 10), validation = seg("validation", 90, 10),
        outOfSample = seg("out-of-sample", oosWins, oosLosses), signals = emptyList(),
        edgeConfirmedOutOfSample = false, calibrationNote = "", summary = ""
    )

    @Test
    fun withoutABacktestTheLiveRecordIsUsed() {
        val rt = AgentRuntime()
        assertFalse(rt.hasBacktestEvidence())
        assertEquals(0, rt.evidence().samples)
    }

    @Test
    fun onlyTheOutOfSampleSegmentFeedsTheGate() {
        val rt = AgentRuntime()
        rt.setBacktest(report(oosWins = 70, oosLosses = 30))
        assertTrue(rt.hasBacktestEvidence())
        assertEquals(100, rt.evidence().samples)
        assertEquals(70, rt.evidence().hits) // train/validation (90/10 each) are NOT counted
    }

    @Test
    fun clearingTheBacktestFallsBackToLive() {
        val rt = AgentRuntime()
        rt.setBacktest(report(70, 30))
        rt.setBacktest(null)
        assertFalse(rt.hasBacktestEvidence())
    }
}
