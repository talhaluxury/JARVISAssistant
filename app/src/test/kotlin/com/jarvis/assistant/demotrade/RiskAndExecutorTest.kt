package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAndExecutorTest {
    private val s = testSettings { it.copy(payoutPercent = 90.0, fixedStake = 10.0, cooldownSeconds = 0, cooldownAfterLossSeconds = 0) }
    private fun fresh() = DemoAccount.fresh(10_000.0, T0)

    private fun open(acct: DemoAccount, dir: Dir = Dir.CALL, settings: DemoSettings = s) =
        PaperTradeExecutor.open(acct, 1, signal(dir, 80, T0, 1.2345, s = settings), 10.0, settings, T0)

    @Test
    fun winPaysPayoutOnTopOfStake() {
        val (a1, t) = open(fresh())
        assertEquals(9_990.0, a1.balance, 1e-9)
        val (a2, closed) = PaperTradeExecutor.settle(a1, t, 1.2350, PaperTradeExecutor.decide(Dir.CALL, 1.2345, 1.2350), T0 + 60_000L)
        assertEquals(TradeResult.WIN, closed.result)
        assertEquals(9.0, closed.pnl!!, 1e-9)
        assertEquals(10_009.0, a2.balance, 1e-9)
        assertEquals(0.0, a2.openStake, 1e-9)
        assertEquals(0, a2.consecutiveLosses)
    }

    @Test
    fun lossLosesTheWholeStake() {
        val (a1, t) = open(fresh(), Dir.PUT)
        val (a2, closed) = PaperTradeExecutor.settle(a1, t, 1.2350, PaperTradeExecutor.decide(Dir.PUT, 1.2345, 1.2350), T0 + 60_000L)
        assertEquals(TradeResult.LOSS, closed.result)
        assertEquals(-10.0, closed.pnl!!, 1e-9)
        assertEquals(9_990.0, a2.balance, 1e-9)
        assertEquals(1, a2.consecutiveLosses)
    }

    @Test
    fun tieRefundsOrLosesAccordingToSettings() {
        assertEquals(TradeResult.TIE, PaperTradeExecutor.decide(Dir.CALL, 1.0, 1.0))
        val (a1, t) = open(fresh())
        val (a2, c) = PaperTradeExecutor.settle(a1, t, 1.2345, TradeResult.TIE, T0 + 60_000L)
        assertEquals(10_000.0, a2.balance, 1e-9)
        assertEquals(0.0, c.pnl!!, 1e-9)

        val loseTies = s.copy(tieMode = TieMode.LOSS)
        val (b1, t2) = open(fresh(), Dir.CALL, loseTies)
        val (b2, c2) = PaperTradeExecutor.settle(b1, t2, 1.2345, TradeResult.TIE, T0 + 60_000L)
        assertEquals(9_990.0, b2.balance, 1e-9)
        assertEquals(-10.0, c2.pnl!!, 1e-9)
    }

    @Test
    fun voidRefundsTheStake() {
        val (a1, t) = open(fresh())
        val (a2, c) = PaperTradeExecutor.settle(a1, t, null, TradeResult.VOID, T0 + 90_000L)
        assertEquals(10_000.0, a2.balance, 1e-9)
        assertEquals(TradeResult.VOID, c.result)
        assertEquals(0, a2.consecutiveLosses)
    }

    @Test
    fun payoutIsConfigurable() {
        val seventySeven = s.copy(payoutPercent = 77.0)
        val (a1, t) = open(fresh(), Dir.CALL, seventySeven)
        val (_, c) = PaperTradeExecutor.settle(a1, t, 2.0, TradeResult.WIN, T0 + 60_000L)
        assertEquals(7.7, c.pnl!!, 1e-9)
        assertEquals(1.0 / 1.77, seventySeven.breakEvenWinRate, 1e-9)
    }

    @Test
    fun stakeNeverGrowsAfterLosses() {
        var acct = fresh()
        val sig = signal(Dir.CALL, 80, T0)
        val first = RiskManager.stakeFor(sig, acct, s)
        repeat(3) {
            val (a, t) = open(acct)
            acct = PaperTradeExecutor.settle(a, t, 0.5, TradeResult.LOSS, T0).first
            assertTrue(RiskManager.stakeFor(sig, acct, s) <= first)
        }
    }

    @Test
    fun stakeModesRespectCaps() {
        val acct = fresh()
        val sig = signal(Dir.CALL, 80, T0, risk = RiskLevel.LOW)
        assertEquals(10.0, RiskManager.stakeFor(sig, acct, s.copy(stakeMode = StakeMode.FIXED)), 1e-9)
        assertEquals(50.0, RiskManager.stakeFor(sig, acct, s.copy(stakeMode = StakeMode.PERCENTAGE, stakePercent = 0.5)), 1e-9)
        assertEquals(100.0, RiskManager.stakeFor(sig, acct, s.copy(stakeMode = StakeMode.RISK_BASED, riskPercent = 1.0)), 1e-9)
        // maxStake cap
        assertEquals(20.0, RiskManager.stakeFor(sig, acct, s.copy(stakeMode = StakeMode.FIXED, fixedStake = 500.0, maxStake = 20.0)), 1e-9)
        // per-trade risk cap (2% of 10,000 = 200)
        assertEquals(200.0, RiskManager.stakeFor(sig, acct, s.copy(stakeMode = StakeMode.FIXED, fixedStake = 5_000.0, maxStake = 5_000.0)), 1e-9)
        // never more than the balance
        val tiny = acct.copy(balance = 3.0)
        assertTrue(RiskManager.stakeFor(sig, tiny, s.copy(fixedStake = 50.0)) <= 3.0)
    }

    @Test
    fun approvesAHealthySignal() {
        val d = RiskManager.evaluate(signal(Dir.CALL, 80, T0), fresh(), s, T0, 0, AdaptiveAdvice.NONE)
        assertTrue(d.reason, d.approved)
        assertEquals(10.0, d.stake, 1e-9)
    }

    @Test
    fun rejectsWaitLowConfidenceAndExpiredSignals() {
        assertFalse(RiskManager.evaluate(signal(Dir.WAIT, 80, T0), fresh(), s, T0, 0, AdaptiveAdvice.NONE).approved)
        assertFalse(RiskManager.evaluate(signal(Dir.CALL, 60, T0), fresh(), s, T0, 0, AdaptiveAdvice.NONE).approved)
        val late = RiskManager.evaluate(signal(Dir.CALL, 90, T0, validForMs = 20_000L), fresh(), s, T0 + 21_000L, 0, AdaptiveAdvice.NONE)
        assertFalse(late.approved)
        assertTrue(late.reason.contains("expired"))
    }

    @Test
    fun dailyLossLimitStopsTrading() {
        val acct = fresh().copy(dayPnl = -500.0, balance = 9_500.0, peakEquity = 10_000.0)
        assertEquals(HaltReason.DAILY_LOSS, RiskManager.checkHalts(acct, s)) // 5% of 10,000
        val d = RiskManager.evaluate(signal(Dir.CALL, 95, T0), acct, s, T0, 0, AdaptiveAdvice.NONE)
        assertFalse(d.approved)
        assertTrue(d.reason.contains("Daily risk limit reached"))
        val ok = fresh().copy(dayPnl = -499.0, balance = 9_501.0)
        assertEquals(HaltReason.NONE, RiskManager.checkHalts(ok, s))
    }

    @Test
    fun consecutiveLossProtectionTriggers() {
        val acct = fresh().copy(consecutiveLosses = s.maxConsecutiveLosses)
        assertEquals(HaltReason.CONSECUTIVE_LOSSES, RiskManager.checkHalts(acct, s))
    }

    @Test
    fun maxDrawdownTriggers() {
        val acct = fresh().copy(balance = 7_900.0, peakEquity = 10_000.0, dayStartBalance = 7_900.0)
        assertEquals(HaltReason.MAX_DRAWDOWN, RiskManager.checkHalts(acct, s))
    }

    @Test
    fun cooldownsAndHourlyLimitBlock() {
        val withCooldown = s.copy(cooldownSeconds = 120)
        val acct = fresh().copy(lastOpenMs = T0 - 30_000L)
        val d = RiskManager.evaluate(signal(Dir.CALL, 90, T0), acct, withCooldown, T0, 0, AdaptiveAdvice.NONE)
        assertFalse(d.approved)
        assertTrue(d.reason.contains("cooldown"))

        val lossCooldown = s.copy(cooldownAfterLossSeconds = 300)
        val afterLoss = fresh().copy(consecutiveLosses = 1, lastLossMs = T0 - 60_000L)
        assertFalse(RiskManager.evaluate(signal(Dir.CALL, 99, T0), afterLoss, lossCooldown, T0, 0, AdaptiveAdvice.NONE).approved)

        val hourly = s.copy(maxTradesPerHour = 2)
        val busy = fresh().copy(recentOpenTimes = listOf(T0 - 600_000L, T0 - 300_000L))
        val h = RiskManager.evaluate(signal(Dir.CALL, 90, T0), busy, hourly, T0, 0, AdaptiveAdvice.NONE)
        assertFalse(h.approved)
        assertTrue(h.reason.contains("hourly"))
    }

    @Test
    fun onlyOneTradeAtATimeByDefault() {
        val d = RiskManager.evaluate(signal(Dir.CALL, 90, T0), fresh(), s, T0, 1, AdaptiveAdvice.NONE)
        assertFalse(d.approved)
    }

    @Test
    fun afterALossTheBarIsHigher() {
        val acct = fresh().copy(consecutiveLosses = 1, lastLossMs = T0 - 10 * 60_000L)
        assertEquals(s.minConfidence + s.postLossExtraConfidence, RiskManager.requiredConfidence(acct, s, T0, AdaptiveAdvice.NONE))
        assertEquals(s.minConfidence, RiskManager.requiredConfidence(fresh(), s, T0, AdaptiveAdvice.NONE))
    }

    @Test
    fun settingsAreClampedSoALimitCannotBeDisabledByTypo() {
        val c = DemoSettings(maxDailyLossPercent = 0.0, maxStake = -5.0, minConfidence = 1, maxTradesPerHour = 0, payoutPercent = 500.0).coerced()
        assertTrue(c.maxDailyLossPercent >= 0.5)
        assertTrue(c.maxStake >= 1.0)
        assertTrue(c.minConfidence >= c.weakThreshold)
        assertTrue(c.maxTradesPerHour >= 1)
        assertTrue(c.payoutPercent <= 100.0)
    }

    @Test
    fun strengthBandsFollowTheConfiguredThresholds() {
        assertEquals(SignalStrength.NONE, s.strengthOf(54))
        assertEquals(SignalStrength.WEAK, s.strengthOf(60))
        assertEquals(SignalStrength.VALID, s.strengthOf(75))
        assertEquals(SignalStrength.STRONG, s.strengthOf(85))
        assertEquals(SignalStrength.VERY_STRONG, s.strengthOf(95))
        val custom = s.copy(strongThreshold = 90, veryStrongThreshold = 95).coerced()
        assertEquals(SignalStrength.VALID, custom.strengthOf(85))
    }
}
