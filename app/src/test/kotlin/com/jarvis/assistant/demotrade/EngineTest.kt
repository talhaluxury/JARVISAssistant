package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private fun setup(s: DemoSettings = testSettings { it.copy(cooldownSeconds = 0, cooldownAfterLossSeconds = 0, payoutPercent = 90.0) }): Pair<Clock, DemoTradingEngine> {
        val clock = Clock()
        return Pair(clock, engineWith(clock, s))
    }

    @Test
    fun opensOneTradeAndDeductsTheStake() {
        val (clock, engine) = setup()
        assertTrue(engine.submitSignal(signal(Dir.CALL, 85, clock.now), clock.now))
        val acct = engine.accountSnapshot()
        assertEquals(9_990.0, acct.balance, 1e-9)
        assertEquals(10.0, acct.openStake, 1e-9)
        assertEquals(1, engine.activeSnapshot().size)
        assertEquals(1, engine.state.value.activeTrades.size)
    }

    @Test
    fun autoModeOffNeverOpensTrades() {
        val (clock, engine) = setup(testSettings { it.copy(autoDemoTrading = false) })
        assertFalse(engine.submitSignal(signal(Dir.CALL, 99, clock.now), clock.now))
        assertEquals(0, engine.activeSnapshot().size)
        assertEquals(10_000.0, engine.accountSnapshot().balance, 1e-9)
    }

    @Test
    fun duplicateAndFlippedSignalsAreNotTraded() {
        val (clock, engine) = setup(testSettings { it.copy(cooldownSeconds = 0, maxConcurrentTrades = 3) })
        assertTrue(engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1), clock.now))
        clock.advance(5_000L)
        // identical signal (same direction, same price) right after
        assertFalse(engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1), clock.now))
        // opposite direction right after: anti-flip
        assertFalse(engine.submitSignal(signal(Dir.PUT, 90, clock.now, entry = 1.1001), clock.now))
        assertEquals(1, engine.activeSnapshot().size)
    }

    @Test
    fun settlesAtExpiryWithTheFirstValidPrice() {
        val (clock, engine) = setup()
        engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1000), clock.now)
        clock.advance(30_000L)
        engine.onTick(clock.now, 1.1010)
        assertEquals(1, engine.activeSnapshot().size) // still running, not settled early
        clock.advance(31_000L)
        engine.onTick(clock.now, 1.1020)
        assertEquals(0, engine.activeSnapshot().size)
        val closed = engine.closedSnapshot().single()
        assertEquals(TradeResult.WIN, closed.result)
        assertEquals(9.0, closed.pnl!!, 1e-9)
        assertEquals(10_009.0, engine.accountSnapshot().balance, 1e-9)
        assertTrue(closed.explanation.isNotBlank())
        assertTrue(closed.maxFavorable > 0.0)
    }

    @Test
    fun lossIsRecordedAndCountsTowardsConsecutiveLosses() {
        val (clock, engine) = setup()
        engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1000), clock.now)
        clock.advance(61_000L)
        engine.onTick(clock.now, 1.0990)
        assertEquals(TradeResult.LOSS, engine.closedSnapshot().single().result)
        assertEquals(1, engine.accountSnapshot().consecutiveLosses)
        assertEquals(9_990.0, engine.accountSnapshot().balance, 1e-9)
    }

    @Test
    fun voidsInsteadOfInventingAResultWhenNoPriceArrives() {
        val (clock, engine) = setup()
        engine.submitSignal(signal(Dir.CALL, 85, clock.now), clock.now)
        clock.advance(60_000L + 20_000L)
        engine.onTick(clock.now, null)
        val closed = engine.closedSnapshot().single()
        assertEquals(TradeResult.VOID, closed.result)
        assertEquals(10_000.0, engine.accountSnapshot().balance, 1e-9)
    }

    @Test
    fun doesNotSettleOnMissingPriceInsideTheGracePeriod() {
        val (clock, engine) = setup()
        engine.submitSignal(signal(Dir.CALL, 85, clock.now), clock.now)
        clock.advance(65_000L)
        engine.onTick(clock.now, null)
        assertEquals(1, engine.activeSnapshot().size)
    }

    @Test
    fun consecutiveLossesStopAutoTradingAndNotify() {
        val events = ArrayList<DemoEvent>()
        val clock = Clock()
        val s = testSettings { it.copy(cooldownSeconds = 0, cooldownAfterLossSeconds = 0, maxConsecutiveLosses = 2, maxDailyLossPercent = 50.0, maxDrawdownPercent = 80.0, sessionLossPercent = 80.0) }
        val engine = engineWith(clock, s, events = events)
        var entry = 1.1
        repeat(2) {
            assertTrue(engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = entry, s = s), clock.now))
            clock.advance(61_000L)
            engine.onTick(clock.now, entry - 0.001)
            clock.advance(200_000L)
            entry += 0.01
        }
        assertEquals(HaltReason.CONSECUTIVE_LOSSES, engine.state.value.halt)
        assertFalse(engine.submitSignal(signal(Dir.CALL, 99, clock.now, entry = entry, s = s), clock.now))
        assertTrue(events.any { it is DemoEvent.RiskHalt })
        assertTrue(events.any { it is DemoEvent.EngineStopped })
        assertTrue(events.count { it is DemoEvent.TradeClosed } == 2)
        engine.resume()
        assertEquals(HaltReason.NONE, engine.state.value.halt)
    }

    @Test
    fun dailyLossLimitStopsTradingAndShowsTheMessage() {
        val clock = Clock()
        val s = testSettings { it.copy(cooldownSeconds = 0, cooldownAfterLossSeconds = 0, fixedStake = 100.0, maxStake = 100.0, maxDailyLossPercent = 1.0, maxConsecutiveLosses = 20) }
        val engine = engineWith(clock, s)
        assertTrue(engine.submitSignal(signal(Dir.CALL, 85, clock.now, s = s), clock.now))
        clock.advance(61_000L)
        engine.onTick(clock.now, 1.0)
        assertEquals(HaltReason.DAILY_LOSS, engine.state.value.halt)
        assertEquals(EnginePhase.RISK_LIMIT, engine.state.value.phase)
        assertTrue(engine.state.value.phaseNote.contains("Daily risk limit reached"))
        clock.advance(300_000L)
        assertFalse(engine.submitSignal(signal(Dir.CALL, 99, clock.now, entry = 1.2, s = s), clock.now))
    }

    @Test
    fun dailyTargetNotifiesOnce() {
        val events = ArrayList<DemoEvent>()
        val clock = Clock()
        val s = testSettings { it.copy(cooldownSeconds = 0, cooldownAfterLossSeconds = 0, fixedStake = 100.0, maxStake = 100.0, dailyTargetPercent = 0.5, payoutPercent = 90.0) }
        val engine = engineWith(clock, s, events = events)
        assertTrue(engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1, s = s), clock.now))
        clock.advance(61_000L)
        engine.onTick(clock.now, 1.2)
        assertEquals(1, events.count { it is DemoEvent.DailyTarget })
    }

    @Test
    fun networkFailureShowsUnavailableAndBlocksNewTrades() {
        val (clock, engine) = setup()
        engine.setFeedUnavailable("no price for 12s", clock.now)
        val st = engine.state.value
        assertEquals(EnginePhase.WAITING_FOR_DATA, st.phase)
        assertTrue(st.dataMessage!!.contains("MARKET DATA UNAVAILABLE"))
        assertTrue(st.dataMessage!!.contains("WAITING FOR DATA"))
        engine.setFeedUnavailable(null, clock.now)
        assertNull(engine.state.value.dataMessage)
        // candles that fail validation keep the engine in WAITING FOR DATA and never trade
        val result = engine.onCandleClosed(emptyList(), MIN_MS, "X", null, clock.now, null)
        assertNull(result)
        assertEquals(0, engine.activeSnapshot().size)
        assertTrue(engine.state.value.dataMessage!!.contains("MARKET DATA UNAVAILABLE"))
    }

    @Test
    fun badDataNeverOpensATrade() {
        val (clock, engine) = setup()
        val candles = flatCandles(80, start = clock.now - 80 * MIN_MS).toMutableList()
        candles[70] = candles[69] // duplicate timestamp
        engine.onCandleClosed(candles, MIN_MS, "X", 1.1, clock.now, null)
        assertEquals(0, engine.activeSnapshot().size)
        assertNotNull(engine.state.value.dataMessage)
        assertTrue(engine.state.value.dataLog.isNotEmpty())
    }

    @Test
    fun theSameCandleIsProcessedOnlyOnce() {
        val (clock, engine) = setup()
        val candles = CandleSimulator.generate(Scenario.STRONG_CALL, 200)
        clock.now = candles.last().openTimeMs + MIN_MS
        engine.onCandleClosed(candles, MIN_MS, "X", candles.last().close, clock.now, null)
        val count = engine.state.value.signals.size
        engine.onCandleClosed(candles, MIN_MS, "X", candles.last().close, clock.now, null)
        assertEquals(count, engine.state.value.signals.size)
        assertTrue(engine.activeSnapshot().size <= 1)
    }

    @Test
    fun accountSurvivesProcessDeath() {
        val store = InMemoryDemoStateStore()
        val clock = Clock()
        val s = testSettings { it.copy(cooldownSeconds = 0) }
        val first = engineWith(clock, s, store)
        first.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1), clock.now)

        val reborn = engineWith(clock, testSettings(), store)
        assertTrue(reborn.restore())
        assertEquals(1, reborn.activeSnapshot().size)
        assertEquals(9_990.0, reborn.accountSnapshot().balance, 1e-9)
        clock.advance(61_000L)
        reborn.onTick(clock.now, 1.2)
        assertEquals(0, reborn.activeSnapshot().size)
        assertEquals(1, reborn.closedSnapshot().size)
        clock.advance(400_000L) // outside the anti-flip window
        // ids keep counting up instead of colliding
        reborn.submitSignal(signal(Dir.PUT, 85, clock.now, entry = 1.3), clock.now)
        assertEquals(2, reborn.activeSnapshot().single().id)
    }

    @Test
    fun stateRoundTripsThroughTheJsonFile() {
        val file = java.io.File.createTempFile("demo_state", ".json")
        try {
            val store = FileDemoStateStore(file)
            val clock = Clock()
            val engine = engineWith(clock, testSettings { it.copy(cooldownSeconds = 0) }, store)
            engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = 1.1), clock.now)
            clock.advance(61_000L)
            engine.onTick(clock.now, 1.2)
            val loaded = store.load()
            assertNotNull(loaded)
            assertEquals(1, loaded!!.closed.size)
            assertEquals(TradeResult.WIN, loaded.closed.single().result)
            assertEquals(engine.accountSnapshot().balance, loaded.account.balance, 1e-9)
            assertEquals(engine.settingsSnapshot(), loaded.settings)
        } finally {
            file.delete()
        }
    }

    @Test
    fun resetRestoresTheStartingBalanceAndClearsHistory() {
        val (clock, engine) = setup()
        engine.submitSignal(signal(Dir.CALL, 85, clock.now), clock.now)
        clock.advance(61_000L)
        engine.onTick(clock.now, 0.5)
        engine.resetAccount()
        assertEquals(10_000.0, engine.accountSnapshot().balance, 1e-9)
        assertEquals(0, engine.closedSnapshot().size)
        assertEquals(0, engine.state.value.stats.total)
    }

    @Test
    fun alertsAreLabelledAndOrdered() {
        val events = ArrayList<DemoEvent>()
        val clock = Clock()
        val engine = engineWith(clock, testSettings { it.copy(cooldownSeconds = 0) }, events = events)
        engine.submitSignal(signal(Dir.CALL, 92, clock.now), clock.now)
        assertTrue(events.first() is DemoEvent.StrongSignal)
        assertTrue(events.any { it is DemoEvent.TradeOpened })
    }
}
