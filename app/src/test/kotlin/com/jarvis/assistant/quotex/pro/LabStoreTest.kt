package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.quotex.agent.EconomicEvent
import com.jarvis.assistant.quotex.agent.EventImpact
import com.jarvis.assistant.quotex.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabStoreTest {
    private val spec = RuleSpec("r1", "my rule", callSide = false, rsiLo = 40.0, rsiHi = 60.0, status = LabStatus.PAPER_TESTING)

    @Test fun ruleRoundTrips() {
        assertEquals(spec, RuleSpec.decode(spec.encode()))
        assertEquals(listOf(spec), LabRuleStore.parse(LabRuleStore.serialize(listOf(spec))))
    }

    @Test fun corruptedRulesAreSkippedNotCrash() {
        assertNull(RuleSpec.decode("garbage"))
        assertNull(RuleSpec.decode("a|b|notbool|1|2|true|1.0|2.0|true|3.0|true|DRAFT"))
        assertEquals(1, LabRuleStore.parse("junk\n" + spec.encode() + "\n\n|||").size)
    }

    @Test fun nameWithSeparatorIsSanitised() {
        val s = spec.copy(name = "a|b\nc")
        assertEquals("a b c", RuleSpec.decode(s.encode())!!.name)
    }

    @Test fun neighboursCoverEveryUsedParameter() {
        val n = RuleSpec("x", "n").neighbours()
        assertTrue(n.size >= 7)
        assertTrue(n.containsKey("base"))
        assertEquals(0, RuleSpec("x", "n", useEma = false, useRsi = false, useAdx = false).neighbours().size - 1)
    }

    @Test fun putRuleUsesEmaBelow() {
        val conds = spec.toRule().conditions
        assertTrue(conds.first() is RuleCondition.EmaBelow)
    }

    @Test fun sweepNeedsEnoughTradesOtherwiseEmpty() {
        val flat = List(200) { Candle(it * 60_000L, 100.0, 100.1, 99.9, 100.0) }
        assertTrue(LabBacktester.sweep(RuleSpec("x", "n"), flat).isEmpty())
    }

    private fun rising(n: Int) = List(n) { i ->
        val p = 100.0 + i * 0.1
        Candle(i * 60_000L, p, p + 0.05, p - 0.05, p + 0.08)
    }

    @Test fun paperTrackerEntersAtCloseAndSettlesLater() {
        val tracker = LabPaperTracker(expiryCandles = 2, candleMs = 60_000L)
        val rule = StrategyRule("r", "rsi any", listOf(RuleCondition.RsiBetween(0.0, 100.0)), LabStatus.PAPER_TESTING, true)
        val c = rising(60)
        tracker.onCandleClosed(listOf(rule), c.take(30))    // enters at the close of candle 29
        assertEquals(0, tracker.stat("r").trades)
        tracker.onCandleClosed(listOf(rule), c.take(31))    // still waiting; nothing is peeked
        assertEquals(0, tracker.stat("r").trades)
        val changed = tracker.onCandleClosed(listOf(rule), c.take(32)) // candle 31 opens at entry + 2 candles -> settles
        assertTrue(changed)
        assertEquals(1, tracker.stat("r").trades)
        assertEquals(1, tracker.stat("r").wins)             // rising series, CALL side wins
    }

    @Test fun paperStatsPersistAndSurviveCorruption() {
        val t = LabPaperTracker(2, 60_000L)
        t.load("r|10|6|1.5\nbroken\nq|x|y|z")
        assertEquals(10, t.stat("r").trades)
        assertEquals(0, t.stat("q").trades)
        assertTrue(t.dump().startsWith("r|10|6|"))
        t.reset("r")
        assertEquals(0, t.stat("r").trades)
    }

    @Test fun newsEventsRoundTripAndSkipCorrupt() {
        val ev = listOf(EconomicEvent(1_000L, "NFP | release", EventImpact.HIGH))
        val back = NewsEventCodec.parse(NewsEventCodec.serialize(ev) + "\nnope\n1|BAD|x")
        assertEquals(1, back.size)
        assertEquals(EventImpact.HIGH, back[0].impact)
        assertEquals("NFP   release", back[0].title)
    }
}
