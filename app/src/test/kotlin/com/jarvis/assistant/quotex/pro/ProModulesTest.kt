package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProModulesTest {

    private fun flat(n: Int): MutableList<Candle> =
        MutableList(n) { Candle(it * 60_000L, 100.0, 100.5, 99.5, 100.2) }

    private fun mirror(c: List<Candle>) = c.map { Candle(it.openTimeMs, -it.open, -it.low, -it.high, -it.close) }

    private fun fakeUp(): List<Candle> {
        val c = flat(30)
        c += Candle(30 * 60_000L, 100.2, 101.5, 100.1, 100.3) // pokes above 100.5, long upper wick, weak close
        c += Candle(31 * 60_000L, 100.4, 100.45, 99.8, 99.9)  // back below the level, bearish
        return c
    }

    @Test fun fakeBreakoutUpDetected() {
        val r = FakeBreakoutDetector.detect(fakeUp())
        assertEquals(FakeBreakKind.FAKE_BREAKOUT_UP, r.kind)
        assertTrue(r.quality >= 60)
    }

    @Test fun fakeBreakdownDetectedByMirror() {
        val r = FakeBreakoutDetector.detect(mirror(fakeUp()))
        assertEquals(FakeBreakKind.FAKE_BREAKDOWN, r.kind)
    }

    @Test fun realBreakoutIsNotFake() {
        val c = flat(30)
        c += Candle(30 * 60_000L, 100.2, 102.2, 100.1, 102.0)
        c += Candle(31 * 60_000L, 102.0, 102.8, 101.9, 102.5)
        assertEquals(FakeBreakKind.NONE, FakeBreakoutDetector.detect(c).kind)
    }

    @Test fun tooFewCandlesGivesNone() {
        assertEquals(FakeBreakKind.NONE, FakeBreakoutDetector.detect(flat(5)).kind)
    }

    @Test fun lateEntryForcesWait() {
        val a = EntryQualityEvaluator.evaluate(true, 103.0, 100.0, 99.0, 1.0, 5, 5)
        assertEquals(EntryQuality.LATE, a.quality)
        assertEquals(QuotexDecision.WAIT, EntryQualityEvaluator.gate(QuotexDecision.CALL, a))
    }

    @Test fun optimalEntryKeepsDirection() {
        val a = EntryQualityEvaluator.evaluate(true, 100.6, 100.0, 99.0, 1.0, 5, 5)
        assertEquals(EntryQuality.OPTIMAL, a.quality)
        assertEquals(QuotexDecision.CALL, EntryQualityEvaluator.gate(QuotexDecision.CALL, a))
    }

    @Test fun earlyFormingAndInvalid() {
        assertEquals(EntryQuality.EARLY, EntryQualityEvaluator.evaluate(false, 99.8, 100.0, 101.0, 1.0, 1, 5).quality)
        assertEquals(EntryQuality.FORMING, EntryQualityEvaluator.evaluate(false, 99.8, 100.0, 101.0, 1.0, 3, 5).quality)
        assertEquals(EntryQuality.INVALID, EntryQualityEvaluator.evaluate(true, 98.9, 100.0, 99.0, 1.0, 5, 5).quality)
        assertEquals(EntryQuality.INVALID, EntryQualityEvaluator.evaluate(true, 100.0, 100.0, null, 0.0, 5, 5).quality)
    }

    @Test fun repeatedFailuresInSameRegimeGiveWait() {
        val m = SignalMemory()
        repeat(3) { i ->
            m.record("pullback", "TREND", QuotexDecision.CALL, i * 300_000L)
            m.resolve("pullback", MemoryOutcome.LOSS)
        }
        val v = m.verdict("pullback", "TREND", QuotexDecision.CALL, 2_000_000L)
        assertEquals(MemoryAction.WAIT, v.action)
        assertEquals(MemoryAction.OK, m.verdict("pullback", "RANGE", QuotexDecision.CALL, 2_000_000L).action)
    }

    @Test fun alternatingDirectionIsUnstable() {
        val m = SignalMemory()
        val dirs = listOf(QuotexDecision.CALL, QuotexDecision.PUT, QuotexDecision.CALL, QuotexDecision.PUT)
        dirs.forEachIndexed { i, d -> m.record("k$i", "R", d, i * 120_000L) }
        assertEquals(MemoryAction.WAIT, m.verdict("new", "R", QuotexDecision.CALL, 600_000L).action)
    }

    @Test fun duplicateSetupIsFlagged() {
        val m = SignalMemory()
        m.record("same", "R", QuotexDecision.CALL, 1_000L)
        assertTrue(m.verdict("same", "R", QuotexDecision.CALL, 20_000L).duplicate)
        assertFalse(m.verdict("same", "R", QuotexDecision.CALL, 500_000L).duplicate)
    }

    @Test fun monteCarloIsDeterministicAndWarnsOnSmallSample() {
        val pnl = List(20) { if (it % 2 == 0) 0.85 else -1.0 }
        val a = MonteCarlo.simulate(pnl, runs = 200)
        val b = MonteCarlo.simulate(pnl, runs = 200)
        assertEquals(a.medianMaxDrawdown, b.medianMaxDrawdown, 1e-12)
        assertTrue(a.warnings.any { it.contains("Only 20 trades") })
        assertTrue(a.warnings.any { it.contains("Expectancy is not positive") })
        assertEquals(1.0 / 1.85, MonteCarlo.breakEvenWinRate(0.85), 1e-12)
    }

    @Test fun robustnessNeedsNeighbours() {
        assertEquals(Robustness.INSUFFICIENT, ParameterRobustness.assess(mapOf("a" to 0.1)).first)
        val fragile = mapOf("1" to -0.1, "2" to -0.2, "3" to 0.3, "4" to -0.1, "5" to -0.05)
        assertEquals(Robustness.FRAGILE, ParameterRobustness.assess(fragile).first)
        val robust = mapOf("1" to 0.1, "2" to 0.2, "3" to 0.3, "4" to 0.1, "5" to -0.05)
        assertEquals(Robustness.ROBUST, ParameterRobustness.assess(robust).first)
    }

    @Test fun newStrategyCannotBeEnabledWithoutValidation() {
        val weak = ValidationSummary(20, 0.2, 1, 2, Robustness.INSUFFICIENT, 0, 0.0)
        val g = StrategyDeployGate.canEnable(weak)
        assertFalse(g.allowed)
        assertTrue(g.reasons.size >= 4)
        val strong = ValidationSummary(150, 0.08, 4, 5, Robustness.ROBUST, 40, 0.05)
        assertTrue(StrategyDeployGate.canEnable(strong).allowed)
    }

    @Test fun ruleConditionsNeedEnoughData() {
        val s = PriceSeries(flat(10))
        assertFalse(StrategyRule("r", "x", listOf(RuleCondition.EmaAbove(21, 50))).matches(s))
        assertFalse(StrategyRule("r", "empty", emptyList()).matches(s))
    }
}

class LabAndScoreTest {
    private fun trend(n: Int) = List(n) { i ->
        val base = 100.0 + i * 0.05 + Math.sin(i / 3.0) * 0.4
        Candle(i * 60_000L, base, base + 0.3, base - 0.3, base + 0.1)
    }

    @org.junit.Test fun labBacktestHasNoLookAhead() {
        val all = trend(300)
        val rule = StrategyRule("r", "ema", listOf(RuleCondition.EmaAbove(9, 21)))
        val full = LabBacktester.run(rule, all)
        val cut = LabBacktester.run(rule, all.take(200))
        // Trades decided inside the first 200 candles must be identical when future candles are removed.
        val common = minOf(cut.pnl.size - 1, full.pnl.size).coerceAtLeast(0)
        assertEquals(cut.pnl.take(common), full.pnl.take(common))
    }

    @org.junit.Test fun segmentsAreChronological() {
        val r = LabBacktester.run(StrategyRule("r", "x", listOf(RuleCondition.EmaAbove(9, 21))), trend(300))
        assertEquals(listOf("TRAIN", "VALIDATION", "TEST"), r.segments.map { it.name })
    }

    @org.junit.Test fun lateEntryCapsScore() {
        val strong = ScoreInput(1.0, 1.0, true, 96, 90, 0, EntryQuality.OPTIMAL)
        val late = strong.copy(entryQuality = EntryQuality.LATE)
        assertTrue(SetupScorer.score(strong) >= 90)
        assertTrue(SetupScorer.score(late) <= 59)
    }

    @org.junit.Test fun mtfConflictLowersScore() {
        val a = SetupScorer.score(ScoreInput(0.8, 0.8, true, 90, null, 0, EntryQuality.CONFIRMED))
        val b = SetupScorer.score(ScoreInput(0.8, 0.8, false, 90, null, 0, EntryQuality.CONFIRMED))
        assertTrue(a - b >= 10)
    }

    @org.junit.Test fun smallSampleStatsAreNotShownAsPercent() {
        assertTrue(StatLine("x", 3, 1).display(30).contains("not meaningful"))
        assertTrue(StatLine("x", 40, 20).display(30).contains("%"))
    }

    @org.junit.Test fun explanationNeverInventsAndFlagsIncomplete() {
        val t = ExplanationBuilder.build(ExplanationInput("WAIT", null, "RANGE", null, "POOR", "HIGH", emptyList(), listOf("MTF conflict"), true))
        assertTrue(t.contains("n/a"))
        assertTrue(t.contains("incomplete"))
        assertTrue(t.contains("not a prediction"))
    }

    @org.junit.Test fun drawdownMath() {
        assertEquals(2.0, PerformanceStats.maxDrawdown(listOf(1.0, -1.0, -1.0, 0.5)), 1e-9)
    }
}
