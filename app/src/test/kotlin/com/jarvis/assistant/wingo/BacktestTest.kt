package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.analysis.CallOutcome
import com.jarvis.assistant.wingo.analysis.ModelOutput
import com.jarvis.assistant.wingo.analysis.PerformanceAnalyzer
import com.jarvis.assistant.wingo.analysis.RoundHistory
import com.jarvis.assistant.wingo.analysis.WinGoBacktestEngine
import com.jarvis.assistant.wingo.analysis.WinGoModel
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.domain.WinGoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BacktestTest {

    private fun period(seq: Int) = "2026092010005" + seq.toString().padStart(4, '0')

    private fun rounds(numbers: List<Int>) = numbers.mapIndexed { i, n -> RoundResult(period(i + 1), n, 0L) }

    private class SpyModel : WinGoModel {
        override val name: String = "Spy"
        val seen = ArrayList<List<Int>>()
        override fun predict(history: RoundHistory): ModelOutput {
            seen.add(history.numbers.toList())
            return ModelOutput(name, 0.5, history.size, "spy")
        }
    }

    @Test
    fun backtestNeverShowsAModelAnythingFromTheRoundItPredicts() {
        val numbers = List(130) { it % 10 }
        val spy = SpyModel()
        WinGoBacktestEngine(WinGoConfig()).run(rounds(numbers), listOf(spy))

        assertEquals(30, spy.seen.size) // rounds 100..129
        spy.seen.forEachIndexed { k, visible ->
            val roundIndex = 100 + k
            assertEquals("round $roundIndex must only see earlier rounds", roundIndex, visible.size)
            assertEquals(numbers.subList(0, roundIndex), visible)
        }
    }

    @Test
    fun backtestIsChronologicalAndDeterministicEvenIfInputIsShuffled() {
        val random = Random(11)
        val numbers = List(400) { random.nextInt(10) }
        val ordered = rounds(numbers)
        val shuffled = ordered.shuffled(Random(3))
        val a = WinGoBacktestEngine().run(ordered)
        val b = WinGoBacktestEngine().run(shuffled)
        assertEquals(a.toText(), b.toText())
        assertEquals(400, a.totalRounds)
        assertEquals(300, a.evaluatedRounds)
    }

    @Test
    fun reportCountsAddUpAndVerdictDoesNotOverclaimOnRandomData() {
        val random = Random(21)
        val report = WinGoBacktestEngine().run(rounds(List(2500) { random.nextInt(10) }))
        assertEquals(report.allCalls.calls, report.allCalls.correct + report.allCalls.wrong)
        val accuracy = report.allCalls.accuracy!!
        assertTrue("accuracy on random data should be near 50%, was $accuracy", accuracy in 0.40..0.60)
        assertNotNull(report.verdict)
        assertTrue(report.toText().startsWith("BACKTEST"))
    }

    @Test
    fun tooLittleDataGivesNoVerdict() {
        val report = WinGoBacktestEngine().run(rounds(List(150) { it % 10 }))
        assertTrue(report.verdict.contains("Not enough data"))
    }

    @Test
    fun gapsInPeriodsAreReported() {
        val results = listOf(
            RoundResult(period(1), 1, 0L), RoundResult(period(2), 2, 0L), RoundResult(period(5), 3, 0L)
        )
        assertEquals(1, WinGoBacktestEngine().countGaps(results))
    }

    // ---- performance maths -------------------------------------------------------------------------------

    private fun outcome(correct: Boolean, confidence: Double, signal: Signal = Signal.WAIT) = CallOutcome(
        period = "p", side = BigSmall.BIG, confidence = confidence, level = WinGoConfig().levelFor(confidence),
        signal = signal, agree = 4, totalModels = 7, actual = if (correct) BigSmall.BIG else BigSmall.SMALL
    )

    @Test
    fun streaksAndAccuracyAreComputedFromResolvedCalls() {
        val calls = listOf(true, true, false, false, false, true).map { outcome(it, 0.6) }
        val s = PerformanceAnalyzer.summarize(calls)
        assertEquals(6, s.calls)
        assertEquals(3, s.correct)
        assertEquals(3, s.wrong)
        assertEquals(2, s.maxWinStreak)
        assertEquals(3, s.maxLossStreak)
        assertEquals(1, s.currentStreak)
        assertEquals(0.5, s.accuracy!!, 1e-9)
    }

    @Test
    fun accuracyIsGroupedByConfidenceBand() {
        val calls = listOf(
            outcome(true, 0.51), outcome(false, 0.56), outcome(true, 0.56), outcome(true, 0.62), outcome(false, 0.75)
        )
        val bands = PerformanceAnalyzer.byBand(calls, WinGoConfig())
        assertEquals(1, bands.first { it.level == ConfidenceLevel.VERY_LOW }.calls)
        val low = bands.first { it.level == ConfidenceLevel.LOW }
        assertEquals(2, low.calls)
        assertEquals(1, low.correct)
        assertEquals(1, bands.first { it.level == ConfidenceLevel.MEDIUM }.correct)
        assertEquals(0, bands.first { it.level == ConfidenceLevel.HIGH }.correct)
    }

    @Test
    fun signalledStatsOnlyIncludeSurfacedSignals() {
        val calls = listOf(outcome(true, 0.6, Signal.MEDIUM), outcome(false, 0.6, Signal.WAIT), outcome(true, 0.6, Signal.WAIT))
        val w = PerformanceAnalyzer.window(calls)
        assertEquals(3, w.allCalls.calls)
        assertEquals(1, w.signalled.calls)
    }

    @Test
    fun cumulativeAccuracyIsARunningAverage() {
        val calls = listOf(true, false, true, true).map { outcome(it, 0.6) }
        val curve = PerformanceAnalyzer.cumulativeAccuracy(calls)
        assertEquals(listOf(1.0, 0.5, 2.0 / 3.0, 0.75), curve)
    }
}
