package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test

class RiskManagementEngineTest {

    private val engine = RiskManagementEngine()
    private val settings = TradingSettings() // defaults: 0.5% risk/trade, 2%/6% loss limits, 3 max open, 4% max currency exposure, 1.5 min R:R, 1.0 max lot

    private fun goodSignal(
        pair: CurrencyPair = CurrencyPair.EURUSD,
        entry: Double? = 1.1000,
        stopLoss: Double? = 1.0950,
        takeProfit: Double? = 1.1150,
        rr: Double? = 3.0,
        decision: TradeDecision = TradeDecision.BUY_SETUP
    ) = TradeSignal(
        id = "test-signal", pair = pair, decision = decision, confidenceScore = 80,
        timeframesAnalyzed = listOf(Timeframe.H1), checks = emptyList(),
        entry = entry, stopLoss = stopLoss, takeProfit = takeProfit, riskRewardRatio = rr,
        reason = "test", generatedAtEpochMillis = 1_000_000L
    )

    private fun account(equity: Double = 10_000.0, marginAvailable: Double = 10_000.0) = AccountSnapshot(
        accountId = "demo", balance = equity, equity = equity, marginUsed = 0.0, marginAvailable = marginAvailable, currency = "USD"
    )

    @Test
    fun approvesAGoodSignalWithNoConflicts() {
        val verdict = engine.evaluate(
            signal = goodSignal(), account = account(), currentPrice = 1.1000,
            openPositions = emptyList(), lockState = TradingLockState(),
            dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Approved)
        val approved = verdict as RiskVerdict.Approved
        assertEquals(CurrencyPair.EURUSD, approved.orderRequest.pair)
        assertEquals(TradeDirection.BUY, approved.orderRequest.direction)
        assertTrue(approved.orderRequest.lotSize > 0.0)
        assertTrue(approved.riskPercentOfEquity <= settings.risk.maxRiskPerTradePercent + 0.01)
    }

    @Test
    fun blocksWhenEmergencyStopIsActive() {
        val verdict = engine.evaluate(
            signal = goodSignal(), account = account(), currentPrice = 1.1000,
            openPositions = emptyList(),
            lockState = TradingLockState(emergencyStopActive = true, lockReason = "user requested emergency stop"),
            dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
        assertTrue((verdict as RiskVerdict.Blocked).reason.contains("emergency", ignoreCase = true))
    }

    @Test
    fun blocksWhenDailyLossLimitReached() {
        val verdict = engine.evaluate(
            signal = goodSignal(), account = account(), currentPrice = 1.1000,
            openPositions = emptyList(), lockState = TradingLockState(),
            dailyLossPercentSoFar = 2.5, settings = settings // limit is 2.0%
        )
        assertTrue(verdict is RiskVerdict.Blocked)
    }

    @Test
    fun blocksWhenStopLossIsMissing() {
        val verdict = engine.evaluate(
            signal = goodSignal(stopLoss = null), account = account(), currentPrice = 1.1000,
            openPositions = emptyList(), lockState = TradingLockState(),
            dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
        assertTrue((verdict as RiskVerdict.Blocked).reason.contains("Stop-loss"))
    }

    @Test
    fun blocksWhenRiskRewardBelowMinimum() {
        val verdict = engine.evaluate(
            signal = goodSignal(rr = 1.0), account = account(), currentPrice = 1.1000, // min is 1.5
            openPositions = emptyList(), lockState = TradingLockState(),
            dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
    }

    @Test
    fun blocksWhenNotAnActionableDecision() {
        val verdict = engine.evaluate(
            signal = goodSignal(decision = TradeDecision.WAIT), account = account(), currentPrice = 1.1000,
            openPositions = emptyList(), lockState = TradingLockState(),
            dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
    }

    @Test
    fun blocksAtMaximumOpenTrades() {
        val threeOpen = listOf(
            OpenRiskPosition(CurrencyPair.GBPUSD, TradeDirection.BUY, 0.5),
            OpenRiskPosition(CurrencyPair.USDJPY, TradeDirection.SELL, 0.5),
            OpenRiskPosition(CurrencyPair.AUDUSD, TradeDirection.BUY, 0.5)
        )
        val verdict = engine.evaluate(
            signal = goodSignal(), account = account(), currentPrice = 1.1000,
            openPositions = threeOpen, lockState = TradingLockState(),
            dailyLossPercentSoFar = 0.0, settings = settings // maxOpenTrades default = 3
        )
        assertTrue(verdict is RiskVerdict.Blocked)
        assertTrue((verdict as RiskVerdict.Blocked).reason.contains("Maximum open trades"))
    }

    @Test
    fun blocksDuplicatePositionOnSamePair() {
        val verdict = engine.evaluate(
            signal = goodSignal(pair = CurrencyPair.EURUSD), account = account(), currentPrice = 1.1000,
            openPositions = listOf(OpenRiskPosition(CurrencyPair.EURUSD, TradeDirection.BUY, 0.5)),
            lockState = TradingLockState(), dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
        assertTrue((verdict as RiskVerdict.Blocked).reason.contains("already open"))
    }

    @Test
    fun blocksWhenCurrencyExposureWouldBeExceeded() {
        // GBPUSD already risking 3.6% of the USD leg; adding EURUSD's 0.5% pushes USD to 4.1%,
        // above the default 4.0% maxExposurePerCurrencyPercent.
        val verdict = engine.evaluate(
            signal = goodSignal(pair = CurrencyPair.EURUSD), account = account(), currentPrice = 1.1000,
            openPositions = listOf(OpenRiskPosition(CurrencyPair.GBPUSD, TradeDirection.BUY, 3.6)),
            lockState = TradingLockState(), dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
        assertTrue((verdict as RiskVerdict.Blocked).reason.contains("exposure", ignoreCase = true))
    }

    @Test
    fun blocksWhenMarginIsInsufficient() {
        val verdict = engine.evaluate(
            signal = goodSignal(), account = account(marginAvailable = 1.0), currentPrice = 1.1000,
            openPositions = emptyList(), lockState = TradingLockState(),
            dailyLossPercentSoFar = 0.0, settings = settings
        )
        assertTrue(verdict is RiskVerdict.Blocked)
        assertTrue((verdict as RiskVerdict.Blocked).reason.contains("margin", ignoreCase = true))
    }
}
