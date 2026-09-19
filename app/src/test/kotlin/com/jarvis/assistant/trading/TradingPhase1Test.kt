package com.jarvis.assistant.trading

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TradingPhase1Test {

    @Test(expected = IllegalArgumentException::class)
    fun candleRejectsInconsistentHighLow() {
        Candle(
            pair = CurrencyPair.EURUSD, timeframe = Timeframe.M5, openTimeEpochMillis = 0L,
            open = 1.10, high = 1.05, low = 1.20, close = 1.10, // high < low, low > open/close - invalid
            source = DataSource.DEMO_SIMULATED
        )
    }

    @Test
    fun candleAcceptsConsistentOhlc() {
        val c = Candle(
            pair = CurrencyPair.EURUSD, timeframe = Timeframe.M5, openTimeEpochMillis = 0L,
            open = 1.10, high = 1.12, low = 1.08, close = 1.11,
            source = DataSource.DEMO_SIMULATED
        )
        assertEquals(1.11, c.close, 0.0001)
    }

    @Test
    fun tickValidationCatchesStaleData() {
        val service = MarketDataService(DemoBrokerAdapter(), staleThresholdSeconds = 30)
        val oldTick = PriceTick(
            pair = CurrencyPair.EURUSD, bid = 1.0850, ask = 1.0852,
            timestampEpochMillis = 0L, source = DataSource.DEMO_SIMULATED
        )
        val result = service.validate(oldTick, nowEpochMillis = 60_000L)
        assertTrue(result is DataQuality.Invalid)
    }

    @Test
    fun tickValidationCatchesCrossedSpread() {
        val service = MarketDataService(DemoBrokerAdapter())
        val badTick = PriceTick(
            pair = CurrencyPair.EURUSD, bid = 1.0900, ask = 1.0850, // ask < bid
            timestampEpochMillis = 1000L, source = DataSource.DEMO_SIMULATED
        )
        val result = service.validate(badTick, nowEpochMillis = 1000L)
        assertTrue(result is DataQuality.Invalid)
    }

    @Test
    fun tickValidationAcceptsFreshValidTick() {
        val service = MarketDataService(DemoBrokerAdapter())
        val goodTick = PriceTick(
            pair = CurrencyPair.EURUSD, bid = 1.0850, ask = 1.0852,
            timestampEpochMillis = 1000L, source = DataSource.DEMO_SIMULATED
        )
        assertEquals(DataQuality.Valid, service.validate(goodTick, nowEpochMillis = 2000L))
    }

    @Test
    fun demoBrokerRejectsDuplicateClientOrderId() = runBlocking {
        val broker = DemoBrokerAdapter()
        broker.connect()
        val order = OrderRequest(
            clientOrderId = "order-1", pair = CurrencyPair.EURUSD, direction = TradeDirection.BUY,
            lotSize = 0.1, stopLoss = 1.0800, takeProfit = 1.0950, entryType = EntryType.MARKET
        )
        val first = broker.submitOrder(order)
        assertTrue(first is OrderResult.Filled)
        val second = broker.submitOrder(order)
        assertTrue(second is OrderResult.Rejected)
    }

    @Test
    fun demoBrokerProducesRequestedCandleCount() = runBlocking {
        val broker = DemoBrokerAdapter()
        broker.connect()
        val candles = broker.candles(CurrencyPair.EURUSD, Timeframe.M15, 50)
        assertEquals(50, candles.size)
        // chronological order
        for (i in 1 until candles.size) {
            assertTrue(candles[i].openTimeEpochMillis > candles[i - 1].openTimeEpochMillis)
        }
    }

    @Test
    fun emergencyStopBlocksTrading() {
        val controller = EmergencyStopController { RiskSettings() }
        assertTrue(controller.isTradingAllowed())
        controller.activateEmergencyStop("user requested")
        assertFalse(controller.isTradingAllowed())
        controller.userClearEmergencyStop()
        assertTrue(controller.isTradingAllowed())
    }

    @Test
    fun consecutiveLossesTriggerCooldown() {
        val settings = RiskSettings(maxConsecutiveLosses = 2, cooldownAfterLossMinutes = 15)
        val controller = EmergencyStopController { settings }
        val start = 1_000_000L
        controller.recordLoss(start)
        assertTrue(controller.isTradingAllowed(start)) // one loss, not yet at threshold
        controller.recordLoss(start)
        assertFalse(controller.isTradingAllowed(start)) // hit threshold -> cooldown active
        assertTrue(controller.isTradingAllowed(start + 16 * 60_000L)) // cooldown expired
    }

    @Test
    fun dailyLossLockCannotBeSelfCleared() {
        val controller = EmergencyStopController { RiskSettings() }
        controller.triggerDailyLossLock("daily loss limit reached")
        assertFalse(controller.isTradingAllowed())
        controller.userClearEmergencyStop() // wrong lock type - must not clear it
        assertFalse(controller.isTradingAllowed())
        controller.rolloverNewTradingDay()
        assertTrue(controller.isTradingAllowed())
    }

    @Test
    fun tradeDecisionDirectionMapping() {
        assertEquals(TradeDirection.BUY, TradeDecision.STRONG_BUY_SETUP.direction)
        assertEquals(TradeDirection.SELL, TradeDecision.SELL_SETUP.direction)
        assertNull(TradeDecision.NO_TRADE.direction)
        assertNull(TradeDecision.RISK_BLOCKED.direction)
        assertTrue(TradeDecision.BUY_SETUP.isActionable)
        assertFalse(TradeDecision.WAIT.isActionable)
    }
}
