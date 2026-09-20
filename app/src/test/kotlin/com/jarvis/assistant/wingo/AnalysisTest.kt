package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.analysis.DeviationModel
import com.jarvis.assistant.wingo.analysis.EnsemblePredictor
import com.jarvis.assistant.wingo.analysis.ModelOutput
import com.jarvis.assistant.wingo.analysis.ModelTracker
import com.jarvis.assistant.wingo.analysis.PatternModel
import com.jarvis.assistant.wingo.analysis.RecentFrequencyModel
import com.jarvis.assistant.wingo.analysis.RollingHits
import com.jarvis.assistant.wingo.analysis.RollingWindowModel
import com.jarvis.assistant.wingo.analysis.RoundHistory
import com.jarvis.assistant.wingo.analysis.StreakModel
import com.jarvis.assistant.wingo.analysis.TransitionModel
import com.jarvis.assistant.wingo.analysis.WeightedRecentModel
import com.jarvis.assistant.wingo.analysis.WinGoAnalysisEngine
import com.jarvis.assistant.wingo.analysis.WinGoModel
import com.jarvis.assistant.wingo.domain.WinGoConfig
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AnalysisTest {

    private val big = 9
    private val small = 0

    private fun history(vararg letters: String): RoundHistory {
        val numbers = ArrayList<Int>()
        for (chunk in letters) for (c in chunk) numbers.add(if (c == 'B') big else small)
        return RoundHistory(numbers)
    }

    private fun cycle(pattern: String, times: Int): RoundHistory = history(pattern.repeat(times))

    // ---- individual models -----------------------------------------------------------------------

    @Test
    fun recentFrequencyUsesSmoothedFrequencyOfLastTwentyRounds() {
        val out = RecentFrequencyModel().predict(history("S".repeat(5), "B".repeat(15)))
        assertEquals(BigSmall.BIG, out.prediction)
        assertEquals(16.0 / 22.0, out.probBig, 1e-9)
        assertEquals(20, out.sampleSize)
    }

    @Test
    fun modelsAbstainInsteadOfGuessingWithTooLittleData() {
        val tiny = history("BSB")
        assertTrue(RecentFrequencyModel().predict(tiny).abstained)
        assertTrue(RollingWindowModel().predict(tiny).abstained)
        assertTrue(StreakModel().predict(tiny).abstained)
        assertTrue(TransitionModel().predict(tiny).abstained)
        assertTrue(DeviationModel().predict(tiny).abstained)
        assertTrue(WeightedRecentModel().predict(tiny).abstained)
        assertTrue(PatternModel().predict(tiny).abstained)
        assertNull(RecentFrequencyModel().predict(tiny).prediction)
    }

    @Test
    fun rollingWindowAveragesTheWindowsThatFit() {
        val out = RollingWindowModel().predict(history("B".repeat(10)))
        assertEquals(11.0 / 12.0, out.probBig, 1e-9) // only the 10-round window fits
    }

    @Test
    fun transitionModelLearnsAStrictAlternation() {
        val h = cycle("BS", 100) // ends on S, so the next round in the pattern is B
        val out = TransitionModel().predict(h)
        assertEquals(BigSmall.BIG, out.prediction)
        assertTrue(out.probBig > 0.95)
    }

    @Test
    fun streakModelUsesWhatHistoricallyFollowedTheCurrentStreak() {
        val h = cycle("BBBS", 60) // every S is followed by B
        val out = StreakModel().predict(h)
        assertEquals(BigSmall.BIG, out.prediction)
        assertTrue(out.probBig > 0.95)
    }

    @Test
    fun patternModelFindsARepeatingCycle() {
        val h = cycle("BBSBS", 60) // ends on S; the cycle continues with B
        val out = PatternModel().predict(h)
        assertEquals(BigSmall.BIG, out.prediction)
        assertTrue(out.probBig > 0.9)
    }

    @Test
    fun weightedRecentLeansTowardsRecentDominance() {
        val out = WeightedRecentModel().predict(history("B".repeat(100)))
        assertEquals(BigSmall.BIG, out.prediction)
        assertTrue(out.probBig > 0.9)
    }

    @Test
    fun deviationModelExpectsDriftBackAfterAStrongRecentExcess() {
        val out = DeviationModel().predict(history("S".repeat(100), "B".repeat(50)))
        assertEquals(BigSmall.SMALL, out.prediction)
    }

    // ---- ensemble and weights ----------------------------------------------------------------------

    @Test
    fun ensembleIsAWeightedMeanOfVotingModelsOnly() {
        val outputs = listOf(
            ModelOutput("A", 0.60, 20, "a"),
            ModelOutput("B", 0.55, 20, "b"),
            ModelOutput("C", 0.45, 20, "c"),
            ModelOutput.abstain("D", 3, "d")
        )
        val r = EnsemblePredictor().combine(outputs, mapOf("A" to 1.0, "B" to 1.0, "C" to 1.0, "D" to 1.0))
        assertEquals(BigSmall.BIG, r.side)
        assertEquals(3, r.voting)
        assertEquals(2, r.agree)
        assertEquals(4, r.totalModels)
        assertEquals((0.60 + 0.55 + 0.45) / 3.0, r.confidence, 1e-9)
    }

    @Test
    fun ensembleWithNoVotesHasNoSide() {
        val r = EnsemblePredictor().combine(listOf(ModelOutput.abstain("A", 0, "x")), mapOf("A" to 1.0))
        assertNull(r.side)
    }

    @Test
    fun trackerWeightsFollowWalkForwardSkill() {
        val young = ModelTracker()
        repeat(10) { young.record(true) }
        assertEquals(1.0, young.weight(), 1e-9) // too few samples to judge

        val good = ModelTracker()
        repeat(100) { good.record(true) }
        assertEquals(2.0, good.weight(), 1e-9)

        val bad = ModelTracker()
        repeat(100) { bad.record(false) }
        assertEquals(0.1, bad.weight(), 1e-9)

        val neutral = ModelTracker()
        repeat(50) { neutral.record(true); neutral.record(false) }
        assertEquals(1.0, neutral.weight(), 1e-9)
    }

    @Test
    fun rollingHitsKeepsOnlyTheWindow() {
        val r = RollingHits(3)
        r.record(true); r.record(true); r.record(true); r.record(false)
        assertEquals(3, r.samples)
        assertEquals(2, r.hits)
    }

    // ---- engine: WAIT logic --------------------------------------------------------------------------

    @Test
    fun engineWaitsWhenHistoryIsInsufficient() {
        val p = WinGoAnalysisEngine().predict(RoundHistory(List(50) { big }))
        assertEquals(Signal.WAIT, p.signal)
        assertNull(p.side)
        assertTrue(p.waitReason!!.contains("Insufficient historical data"))
    }

    private class FixedModel(override val name: String, private val p: Double) : WinGoModel {
        override fun predict(history: RoundHistory): ModelOutput = ModelOutput(name, p, history.size, "fixed")
    }

    @Test
    fun aStrongLeanStillWaitsUntilARealEdgeIsVerified() {
        val models = listOf(FixedModel("A", 0.9), FixedModel("B", 0.85), FixedModel("C", 0.8))
        val h = RoundHistory(List(150) { big })

        val gated = WinGoAnalysisEngine(WinGoConfig(), models).predict(h)
        assertEquals(Signal.WAIT, gated.signal)
        assertTrue(gated.isCandidate)
        assertTrue(gated.waitReason!!.contains("no statistically verified edge"))

        val open = WinGoAnalysisEngine(WinGoConfig(requireVerifiedEdge = false), models).predict(h)
        assertEquals(Signal.HIGH, open.signal)
        assertEquals(BigSmall.BIG, open.side)
    }

    @Test
    fun weakOrDisagreeingModelsProduceWait() {
        val h = RoundHistory(List(150) { big })
        val weak = WinGoAnalysisEngine(WinGoConfig(), listOf(FixedModel("A", 0.52), FixedModel("B", 0.53))).predict(h)
        assertEquals(Signal.WAIT, weak.signal)
        assertTrue(weak.waitReason!!.contains("insufficient signal"))

        val split = listOf(
            FixedModel("A", 0.99), FixedModel("B", 0.99),
            FixedModel("C", 0.45), FixedModel("D", 0.45), FixedModel("E", 0.45)
        )
        val unclear = WinGoAnalysisEngine(WinGoConfig(requireVerifiedEdge = false), split).predict(h)
        assertEquals(Signal.WAIT, unclear.signal)
        assertTrue(unclear.waitReason!!.contains("Signal unclear"))
    }

    @Test
    fun engineSignalsOnceAPredictablePatternHasProvenItself() {
        val numbers = List(1500) { if ("BBSBS"[it % 5] == 'B') big else small }
        val engine = WinGoAnalysisEngine()
        var signalsInLast500 = 0
        var correctInLast500 = 0
        engine.walkForward(numbers, 100) { index, prediction, actual ->
            if (index >= 1000 && prediction.isSignal) {
                signalsInLast500++
                if (prediction.side == actual) correctInLast500++
            }
        }
        assertTrue("expected signals once the edge is proven", signalsInLast500 > 100)
        assertTrue(correctInLast500.toDouble() / signalsInLast500 >= 0.95)
    }

    @Test
    fun randomDataDoesNotProduceAFalseSenseOfAccuracy() {
        val random = Random(7)
        val numbers = List(2000) { random.nextInt(10) }
        val engine = WinGoAnalysisEngine()
        var calls = 0
        var correct = 0
        engine.walkForward(numbers, 100) { _, prediction, actual ->
            val side = prediction.side
            if (side != null) {
                calls++
                if (side == actual) correct++
            }
        }
        val accuracy = correct.toDouble() / calls
        assertTrue("accuracy on random data should hover near 50%, was $accuracy", accuracy in 0.40..0.60)
    }
}
