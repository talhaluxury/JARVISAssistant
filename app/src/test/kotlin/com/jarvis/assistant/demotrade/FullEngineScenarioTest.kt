package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole live engine (validator -> indicators -> regime -> strategies -> scoring -> risk manager -> paper executor ->
 * settlement) driven candle by candle over artificial markets, with a simulated clock. These tests do not assert how many
 * trades a market produces (that is a result, not a rule); they assert the rules the engine must keep in ANY market.
 */
class FullEngineScenarioTest {

    private class Outcome(
        val engine: DemoTradingEngine,
        val settings: DemoSettings,
        val history: InMemoryTradeHistory,
        val maxActive: Int,
        val candlesFed: Int,
        val closed: List<PaperTrade>
    )

    private fun runThroughEngine(scenario: Scenario, count: Int = 600, settings: DemoSettings = testSettings()): Outcome {
        val candles = CandleSimulator.generate(scenario, count)
        val history = InMemoryTradeHistory()
        val clock = Clock(candles.first().openTimeMs + MIN_MS)
        val engine = DemoTradingEngine(settings, null, { clock.get() }, null, history)
        var maxActive = 0
        var fed = 0
        for (i in candles.indices) {
            val c = candles[i]
            clock.now = c.openTimeMs + MIN_MS
            engine.onTick(clock.now, c.close)
            if (i + 1 >= settings.minCandles) {
                engine.onCandleClosed(candles.subList(0, i + 1).toList(), MIN_MS, "SIM", c.close, clock.now, null)
                fed++
            }
            maxActive = maxOf(maxActive, engine.activeSnapshot().size)
        }
        return Outcome(engine, settings, history, maxActive, fed, engine.closedSnapshot())
    }

    private fun assertRulesHeld(o: Outcome) {
        val s = o.settings
        val trades = o.closed.sortedBy { it.openedAtMs }
        assertTrue("never more than ${s.maxConcurrentTrades} trade(s) at once", o.maxActive <= s.maxConcurrentTrades)
        assertEquals("every trade id is unique", trades.size, trades.map { it.id }.toSet().size)
        for (t in trades) {
            assertTrue("stake within limits", t.stake >= RiskManager.MIN_STAKE && t.stake <= s.maxStake + 1e-9)
            assertTrue("confidence ${t.confidence} reached the minimum ${s.minConfidence}", t.confidence >= s.minConfidence)
            assertTrue("a trade closed after it opened", (t.closedAtMs ?: t.expiresAtMs) >= t.openedAtMs)
            val inLastHour = trades.count { it.openedAtMs <= t.openedAtMs && t.openedAtMs - it.openedAtMs < 3_600_000L }
            assertTrue("at most ${s.maxTradesPerHour} trades in any hour", inLastHour <= s.maxTradesPerHour)
        }
        for (i in 1 until trades.size) {
            assertTrue("cooldown between trades", trades[i].openedAtMs - trades[i - 1].openedAtMs >= s.cooldownSeconds * 1000L)
        }
        val perDay = trades.groupingBy { dayKeyOf(it.openedAtMs) }.eachCount()
        assertTrue("daily trade limit", perDay.values.all { it <= s.maxDailyTrades })

        // money is conserved: balance + stakes in play = start + the sum of all settled profit/loss
        val acct = o.engine.accountSnapshot()
        val pnl = trades.sumOf { it.pnl ?: 0.0 }
        assertEquals(s.initialBalance + pnl, acct.equity, 1e-6)

        // the permanent history holds exactly what the engine closed
        assertEquals(trades.size, o.history.size())
    }

    @Test
    fun sidewaysMarketKeepsEveryRuleThroughTheWholeEngine() {
        val o = runThroughEngine(Scenario.SIDEWAYS)
        assertTrue("the engine analysed candles", o.candlesFed > 400)
        assertRulesHeld(o)
        // a sideways market must never switch the engine into trading on every candle
        assertTrue("trades (${o.closed.size}) stay far below the number of candles", o.closed.size < o.candlesFed / 3)
    }

    @Test
    fun mixedMarketKeepsEveryRule() {
        assertRulesHeld(runThroughEngine(Scenario.MIXED, 800))
    }

    @Test
    fun trendingMarketsKeepEveryRule() {
        assertRulesHeld(runThroughEngine(Scenario.STRONG_CALL))
        assertRulesHeld(runThroughEngine(Scenario.STRONG_PUT))
    }

    @Test
    fun volatilityShockKeepsEveryRule() {
        assertRulesHeld(runThroughEngine(Scenario.HIGH_VOLATILITY, 600))
    }

    @Test
    fun autoModeOffNeverTradesInAnyMarket() {
        val off = testSettings { it.copy(autoDemoTrading = false) }
        for (sc in Scenario.values()) {
            val o = runThroughEngine(sc, 400, off)
            assertEquals(0, o.closed.size)
            assertEquals(0, o.engine.activeSnapshot().size)
            assertEquals(off.initialBalance, o.engine.accountSnapshot().balance, 1e-9)
        }
    }

    @Test
    fun theSameMarketGivesTheSameTradesEveryTime() {
        val a = runThroughEngine(Scenario.MIXED, 500).closed.map { Triple(it.openedAtMs, it.direction, it.result) }
        val b = runThroughEngine(Scenario.MIXED, 500).closed.map { Triple(it.openedAtMs, it.direction, it.result) }
        assertEquals(a, b)
    }
}
