package com.jarvis.assistant.demotrade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeHistoryTest {
    private val s = testSettings { it.copy(cooldownSeconds = 0, cooldownAfterLossSeconds = 0, payoutPercent = 90.0) }

    /** Opens and settles one winning CALL trade. */
    private fun winOnce(engine: DemoTradingEngine, clock: Clock, entry: Double) {
        assertTrue(engine.submitSignal(signal(Dir.CALL, 85, clock.now, entry = entry, s = s), clock.now))
        clock.advance(61_000L)
        engine.onTick(clock.now, entry + 0.001)
        clock.advance(200_000L)
    }

    @Test
    fun closedTradeRoundTripsThroughTheDatabaseRow() {
        val clock = Clock()
        val engine = engineWith(clock, s)
        winOnce(engine, clock, 1.1)
        val trade = engine.closedSnapshot().single()
        val row = TradeRowCodec.toEntity(trade)
        assertEquals(trade.id, row.id)
        assertEquals("WIN", row.result)
        assertEquals("CALL", row.direction)
        assertEquals(trade.pnl!!, row.pnl, 1e-12)
        assertEquals(trade, TradeRowCodec.fromEntity(row))
    }

    @Test
    fun anUnreadableRowIsSkippedNotFatal() {
        val clock = Clock()
        val engine = engineWith(clock, s)
        winOnce(engine, clock, 1.1)
        val row = TradeRowCodec.toEntity(engine.closedSnapshot().single())
        assertNull(TradeRowCodec.fromEntity(row.copy(json = "{ this is not json")))
    }

    @Test
    fun closedTradesAreWrittenToTheHistoryAsTheySettle() {
        val clock = Clock()
        val history = InMemoryTradeHistory()
        val engine = DemoTradingEngine(s, null, { clock.get() }, null, history)
        winOnce(engine, clock, 1.1)
        winOnce(engine, clock, 1.2)
        assertEquals(2, history.size())
        assertEquals(listOf(1, 2), history.loadRecent(10).map { it.id })
    }

    @Test
    fun historySurvivesARestartAndTheJsonFileKeepsOnlyASmallCopy() {
        val clock = Clock()
        val store = InMemoryDemoStateStore()
        val history = InMemoryTradeHistory()
        val first = DemoTradingEngine(s, store, { clock.get() }, null, history)
        for (i in 0 until 3) winOnce(first, clock, 1.1 + i * 0.01)
        assertTrue(store.saved!!.closed.size <= DemoTradingEngine.JSON_KEEP_CLOSED)

        val second = DemoTradingEngine(s, store, { clock.get() }, null, history)
        assertTrue(second.restore())
        assertEquals(3, second.closedSnapshot().size)
        assertEquals(first.accountSnapshot().balance, second.accountSnapshot().balance, 1e-9)
        assertEquals(4, store.saved!!.nextId)
    }

    @Test
    fun tradesFromTheOldJsonFileAreMovedIntoTheDatabaseOnce() {
        val clock = Clock()
        val store = InMemoryDemoStateStore()
        val old = DemoTradingEngine(s, store, { clock.get() }, null, null) // old build: no database, trades live in the JSON
        for (i in 0 until 2) winOnce(old, clock, 1.1 + i * 0.01)
        assertEquals(2, store.saved!!.closed.size)

        val history = InMemoryTradeHistory()
        val upgraded = DemoTradingEngine(s, store, { clock.get() }, null, history)
        assertTrue(upgraded.restore())
        assertEquals(2, upgraded.closedSnapshot().size)
        assertEquals(2, history.size())

        val again = DemoTradingEngine(s, store, { clock.get() }, null, history)
        assertTrue(again.restore())
        assertEquals(2, again.closedSnapshot().size)
        assertEquals(2, history.size()) // nothing duplicated
    }

    @Test
    fun resettingTheAccountClearsTheHistory() {
        val clock = Clock()
        val history = InMemoryTradeHistory()
        val engine = DemoTradingEngine(s, InMemoryDemoStateStore(), { clock.get() }, null, history)
        winOnce(engine, clock, 1.1)
        assertEquals(1, history.size())
        engine.resetAccount()
        assertEquals(0, history.size())
        assertEquals(0, engine.closedSnapshot().size)
        assertNotNull(engine.state.value.account)
        winOnce(engine, clock, 1.3) // ids start at 1 again and must not collide with old rows
        assertEquals(listOf(1), history.loadRecent(10).map { it.id })
    }
}
