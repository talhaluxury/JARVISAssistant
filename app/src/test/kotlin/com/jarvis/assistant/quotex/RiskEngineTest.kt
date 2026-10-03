package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.risk.RiskConfig
import com.jarvis.assistant.quotex.risk.RiskEngine
import com.jarvis.assistant.quotex.risk.RiskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RiskEngineTest {

    private var now = 1_700_000_000_000L
    private fun engine(config: RiskConfig = RiskConfig(stakePerTrade = 1.0, dailyLossLimit = 3.0, maxConsecutiveLosses = 3)) =
        RiskEngine(config) { now }

    @Test
    fun startsActiveWithNothingTracked() {
        val s = engine().snapshot()
        assertEquals(RiskState.ACTIVE, s.state)
        assertEquals(0, s.tradesToday)
        assertEquals(0.0, s.dailyPnL, 1e-9)
    }

    @Test
    fun winsAndLossesUseTheFixedStakeAndPayout() {
        val e = engine()
        e.record(won = true, payoutFraction = 0.85)
        e.record(won = false, payoutFraction = 0.85)
        val s = e.snapshot()
        assertEquals(0.85 - 1.0, s.dailyPnL, 1e-9)
        assertEquals(1, s.winsToday)
        assertEquals(1, s.lossesToday)
        assertEquals(2, s.tradesToday)
    }

    @Test
    fun theStakeNeverChangesAfterALoss() {
        // Structural no-martingale guarantee: three losses in a row cost exactly three fixed stakes.
        val e = engine(RiskConfig(stakePerTrade = 2.0, dailyLossLimit = 100.0, maxConsecutiveLosses = 10))
        repeat(3) { e.record(won = false, payoutFraction = 0.85) }
        assertEquals(-6.0, e.snapshot().dailyPnL, 1e-9)
    }

    @Test
    fun consecutiveLossesPauseAndAWinResetsTheStreak() {
        val e = engine(RiskConfig(1.0, 100.0, 3))
        e.record(false, 0.85); e.record(false, 0.85)
        assertEquals(RiskState.ACTIVE, e.snapshot().state)
        e.record(true, 0.85) // streak broken
        assertEquals(0, e.snapshot().consecutiveLosses)
        e.record(false, 0.85); e.record(false, 0.85); e.record(false, 0.85)
        assertEquals(RiskState.PAUSED_CONSECUTIVE_LOSSES, e.snapshot().state)
        assertTrue(e.snapshot().paused)
    }

    @Test
    fun dailyLossLimitPausesTrading() {
        val e = engine(RiskConfig(1.0, 3.0, 100))
        e.record(false, 0.85); e.record(false, 0.85)
        assertEquals(RiskState.ACTIVE, e.snapshot().state)
        e.record(false, 0.85)
        assertEquals(RiskState.PAUSED_DAILY_LOSS, e.snapshot().state)
    }

    @Test
    fun outcomesAreNotCountedWhilePausedSoAHiddenWinCannotQuietlyLiftADailyLossPause() {
        val e = engine(RiskConfig(1.0, 3.0, 100))
        repeat(3) { e.record(false, 0.85) }
        assertEquals(RiskState.PAUSED_DAILY_LOSS, e.snapshot().state)
        val before = e.snapshot()
        repeat(5) { e.record(true, 0.85) } // wins on setups the user is not being shown
        val after = e.snapshot()
        assertEquals(before.dailyPnL, after.dailyPnL, 1e-9)
        assertEquals(before.tradesToday, after.tradesToday)
        assertEquals(RiskState.PAUSED_DAILY_LOSS, after.state)
    }

    @Test
    fun dailyPauseLiftsOnTheNextDayAndTheCountersResetButTheLossStreakDoesNot() {
        val e = engine(RiskConfig(1.0, 100.0, 3))
        repeat(3) { e.record(false, 0.85) }
        assertEquals(RiskState.PAUSED_CONSECUTIVE_LOSSES, e.snapshot().state)
        now += 86_400_000L * 2 // two days later
        val s = e.snapshot()
        assertEquals(0, s.tradesToday)
        assertEquals(0.0, s.dailyPnL, 1e-9)
        assertEquals(3, s.consecutiveLosses) // a losing streak spanning midnight still counts
    }

    @Test
    fun aDailyLossPauseLiftsAutomaticallyTheNextDay() {
        val e = engine(RiskConfig(1.0, 3.0, 100))
        repeat(3) { e.record(false, 0.85) }
        assertEquals(RiskState.PAUSED_DAILY_LOSS, e.snapshot().state)
        now += 86_400_000L * 2
        assertEquals(RiskState.ACTIVE, e.snapshot().state)
    }

    @Test
    fun manualPauseAndResumeWork() {
        val e = engine()
        e.setUserPaused(true)
        assertEquals(RiskState.PAUSED_BY_USER, e.snapshot().state)
        e.setUserPaused(false)
        assertEquals(RiskState.ACTIVE, e.snapshot().state)
    }

    @Test
    fun resumingAcknowledgesAConsecutiveLossCoolDownButNeverLiftsADailyLossPause() {
        val streak = engine(RiskConfig(1.0, 100.0, 3))
        repeat(3) { streak.record(false, 0.85) }
        assertEquals(RiskState.PAUSED_CONSECUTIVE_LOSSES, streak.snapshot().state)
        streak.setUserPaused(false)
        assertEquals(RiskState.ACTIVE, streak.snapshot().state)

        val daily = engine(RiskConfig(1.0, 3.0, 100))
        repeat(3) { daily.record(false, 0.85) }
        daily.setUserPaused(false)
        assertEquals(RiskState.PAUSED_DAILY_LOSS, daily.snapshot().state)
    }

    @Test
    fun raisingTheLimitDeliberatelyLiftsADailyLossPause() {
        val e = engine(RiskConfig(1.0, 3.0, 100))
        repeat(3) { e.record(false, 0.85) }
        assertEquals(RiskState.PAUSED_DAILY_LOSS, e.snapshot().state)
        e.updateConfig(RiskConfig(1.0, 10.0, 100)) // the user deliberately raises the limit themselves
        assertEquals(RiskState.ACTIVE, e.snapshot().state)
    }

    @Test
    fun invalidConfigIsRejected() {
        for (bad in listOf<() -> RiskConfig>(
            { RiskConfig(stakePerTrade = 0.0) }, { RiskConfig(dailyLossLimit = -1.0) }, { RiskConfig(maxConsecutiveLosses = 0) }
        )) {
            try {
                bad()
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun pauseReasonIsHumanReadableAndAbsentWhenActive() {
        val e = engine(RiskConfig(1.0, 3.0, 100))
        assertNull(e.snapshot().reason)
        repeat(3) { e.record(false, 0.85) }
        val reason = e.snapshot().reason
        assertTrue(reason!!.contains("Daily loss limit"))
        assertFalse(reason.contains("\$"))
    }
}

class RiskLimitsTest {
    private var now = 1_700_000_000_000L

    @Test
    fun hourlyFrequencyLimitPausesAndLiftsAsTimePasses() {
        val e = RiskEngine(RiskConfig(1.0, 100.0, 100, maxTradesPerDay = 100, maxTradesPerHour = 3, maxDailyExposure = 1000.0)) { now }
        repeat(3) { e.record(true, 0.85) }
        assertEquals(RiskState.PAUSED_TRADE_FREQUENCY, e.snapshot().state)
        now += 61 * 60_000L
        assertEquals(RiskState.ACTIVE, e.snapshot().state)
    }

    @Test
    fun exposureLimitPausesForTheDay() {
        val e = RiskEngine(RiskConfig(2.0, 100.0, 100, maxTradesPerDay = 100, maxTradesPerHour = 100, maxDailyExposure = 6.0)) { now }
        repeat(3) { e.record(true, 0.85) }
        assertEquals(RiskState.PAUSED_EXPOSURE_LIMIT, e.snapshot().state)
        assertEquals(6.0, e.snapshot().exposureToday, 1e-9)
    }
}
