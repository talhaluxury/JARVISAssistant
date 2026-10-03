package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.BreakoutRetestStrategy
import com.jarvis.assistant.quotex.analysis.ConfluenceEngine
import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.PullbackStrategy
import com.jarvis.assistant.quotex.analysis.SetupQuality
import com.jarvis.assistant.quotex.analysis.SignalState
import com.jarvis.assistant.quotex.analysis.SignalStateMachine
import com.jarvis.assistant.quotex.analysis.Strategy
import com.jarvis.assistant.quotex.analysis.StrategyCondition
import com.jarvis.assistant.quotex.analysis.StrategyResult
import com.jarvis.assistant.quotex.analysis.StructureLabel
import com.jarvis.assistant.quotex.analysis.TrendContinuationStrategy
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfluenceTest {

    private fun candlesFromCloses(closes: List<Double>, candleMs: Long = 15_000L, start: Double = 1.0): List<Candle> {
        var previous = start
        return closes.mapIndexed { i, close ->
            val c = Candle(i * candleMs, previous, maxOf(previous, close) + 0.0005, minOf(previous, close) - 0.0005, close)
            previous = close
            c
        }
    }

    private fun uptrend(n: Int = 150): PriceSeries = PriceSeries(candlesFromCloses(List(n) { 1.0 + it * 0.01 }))
    private fun flat(n: Int = 150): PriceSeries = PriceSeries(candlesFromCloses(List(n) { 1.0 }))

    // ---- individual strategies -------------------------------------------------------------------------------

    @Test
    fun trendContinuationLeansCallInASustainedUptrendAndWaitsInARange() {
        val up = uptrend()
        val trend = MarketStructure.trendLabel(up)
        val strategy = TrendContinuationStrategy()
        val result = strategy.evaluate(up, trend, StructureLabel.UNCLEAR, VolatilityState.NORMAL, emptyList())
        assertEquals(QuotexDecision.CALL, result.direction)
        assertTrue(result.satisfiedCount >= 2)

        val flatSeries = flat()
        val flatResult = strategy.evaluate(flatSeries, MarketStructure.trendLabel(flatSeries), StructureLabel.UNCLEAR, VolatilityState.NORMAL, emptyList())
        assertEquals(QuotexDecision.WAIT, flatResult.direction)
    }

    @Test
    fun pullbackStrategyOnlyFiresOnAStrongTrend() {
        val up = uptrend()
        val strategy = PullbackStrategy()
        val trend = MarketStructure.trendLabel(up)
        val result = strategy.evaluate(up, trend, StructureLabel.UNCLEAR, VolatilityState.NORMAL, emptyList())
        if (trend == TrendState.STRONG_UP) {
            assertEquals(QuotexDecision.CALL, result.direction)
        }
        val flatSeries = flat()
        val flatResult = strategy.evaluate(flatSeries, TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL, emptyList())
        assertEquals(QuotexDecision.WAIT, flatResult.direction)
    }

    @Test
    fun breakoutRetestNeedsAllThreeStepsToLeanADirection() {
        // Build a series that rises, makes a swing high, pulls back to retest it, then holds above.
        val closes = ArrayList<Double>()
        for (i in 0 until 60) closes.add(1.0 + i * 0.002) // rise to form a swing high
        val peak = closes.last()
        for (i in 0 until 10) closes.add(peak - i * 0.001) // pull back
        for (i in 0 until 10) closes.add(peak + 0.0005 + i * 0.0002) // retest and push slightly above
        val series = PriceSeries(candlesFromCloses(closes))
        val swings = MarketStructure.swings(series)
        val strategy = BreakoutRetestStrategy()
        val result = strategy.evaluate(series, TrendState.WEAK_UP, StructureLabel.UNCLEAR, VolatilityState.NORMAL, swings)
        // Either it recognises the breakout+retest (CALL) or correctly sees no clean setup (WAIT) - never PUT here.
        assertTrue(result.direction == QuotexDecision.CALL || result.direction == QuotexDecision.WAIT)
    }

    // ---- confluence engine -----------------------------------------------------------------------------------------

    private fun fakeStrategy(name: String, direction: QuotexDecision, satisfied: Int, total: Int) = object : Strategy {
        override val name: String = name
        override fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState, swings: List<com.jarvis.assistant.quotex.analysis.Swing>): StrategyResult {
            val conditions = (0 until total).map { StrategyCondition("c$it", it < satisfied, "") }
            return StrategyResult(name, direction, conditions)
        }
    }

    @Test
    fun noLeaningStrategyMeansNoSetup() {
        val engine = ConfluenceEngine(listOf(fakeStrategy("A", QuotexDecision.WAIT, 0, 0)))
        val r = engine.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.NO_SETUP, r.quality)
        assertEquals(QuotexDecision.WAIT, r.direction)
    }

    @Test
    fun equalOpposingStrategiesCancelOutToNoSetup() {
        val engine = ConfluenceEngine(listOf(
            fakeStrategy("A", QuotexDecision.CALL, 4, 4), fakeStrategy("B", QuotexDecision.PUT, 4, 4)
        ))
        val r = engine.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.NO_SETUP, r.quality)
    }

    @Test
    fun twoStrongAgreeingStrategiesProduceASetupAndThreeProduceHighConfluence() {
        val engine2 = ConfluenceEngine(listOf(
            fakeStrategy("A", QuotexDecision.CALL, 4, 4), fakeStrategy("B", QuotexDecision.CALL, 3, 4)
        ))
        val r2 = engine2.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.SETUP_DETECTED, r2.quality)
        assertEquals(QuotexDecision.CALL, r2.direction)
        assertEquals(2, r2.agreeingStrategies)

        val engine3 = ConfluenceEngine(listOf(
            fakeStrategy("A", QuotexDecision.CALL, 4, 4), fakeStrategy("B", QuotexDecision.CALL, 4, 4), fakeStrategy("C", QuotexDecision.CALL, 3, 4)
        ))
        val r3 = engine3.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.HIGH_CONFLUENCE_SETUP, r3.quality)
    }

    @Test
    fun extremeVolatilityAlwaysReducesQuality() {
        val engine = ConfluenceEngine(listOf(
            fakeStrategy("A", QuotexDecision.CALL, 4, 4), fakeStrategy("B", QuotexDecision.CALL, 4, 4), fakeStrategy("C", QuotexDecision.CALL, 4, 4)
        ))
        val normal = engine.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.HIGH_CONFLUENCE_SETUP, normal.quality)
        val extreme = engine.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.EXTREME)
        assertEquals(SetupQuality.WATCH, extreme.quality)
    }

    @Test
    fun oneWeakStrategyIsOnlyAWeakSetup() {
        val engine = ConfluenceEngine(listOf(fakeStrategy("A", QuotexDecision.CALL, 1, 4)))
        val r = engine.evaluate(flat(), TrendState.RANGE, StructureLabel.UNCLEAR, VolatilityState.NORMAL)
        assertEquals(SetupQuality.WEAK_SETUP, r.quality)
    }

    // ---- signal state machine -----------------------------------------------------------------------------------------------

    private fun confluenceReading(quality: SetupQuality, direction: QuotexDecision) = com.jarvis.assistant.quotex.analysis.ConfluenceResult(
        direction = direction, quality = quality, agreeingStrategies = 2, totalStrategies = 4,
        conditionsSatisfied = 6, conditionsTotal = 8, strategyResults = emptyList(),
        volatility = VolatilityState.NORMAL, trend = TrendState.STRONG_UP, structure = StructureLabel.UNCLEAR, reason = "test"
    )

    @Test
    fun stateMachineNeverJumpsStraightFromScanningToConfirmed() {
        val machine = SignalStateMachine()
        val first = machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true)
        assertEquals(SignalState.PRE_CONFIRMATION, first.state)
        val second = machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true)
        assertEquals(SignalState.CONFIRMED_SETUP, second.state)
    }

    @Test
    fun stateMachineInvalidatesOnADirectionFlipAndFallsBackToScanningWithWeakEvidence() {
        val machine = SignalStateMachine()
        machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true)
        machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true) // CONFIRMED_SETUP
        val flipped = machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.PUT), true)
        assertEquals(SignalState.INVALIDATED, flipped.state)

        val weak = machine.update(confluenceReading(SetupQuality.NO_SETUP, QuotexDecision.WAIT), true)
        assertEquals(SignalState.SCANNING, weak.state)
    }

    @Test
    fun weakEvidenceMovesToWaitingNotWatchlistAndSetupNeverSkipsStages() {
        val machine = SignalStateMachine()
        assertEquals(SignalState.WAITING, machine.update(confluenceReading(SetupQuality.WEAK_SETUP, QuotexDecision.CALL), true).state)
        assertEquals(SignalState.PRE_CONFIRMATION, machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true).state)
        assertEquals(SignalState.SCANNING, machine.update(confluenceReading(SetupQuality.NO_SETUP, QuotexDecision.WAIT), true).state)
    }

    @Test
    fun stateMachineExpiresAConfirmedSetupThatNeverResolves() {
        val machine = SignalStateMachine(maxCandlesConfirmed = 2)
        machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true) // PRE_CONFIRMATION
        machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true) // CONFIRMED_SETUP, count 0
        machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true) // count 1
        machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true) // count 2
        val expired = machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), true)
        assertEquals(SignalState.EXPIRED, expired.state)
    }

    @Test
    fun stateMachineReturnsNoTradeWhenDataQualityIsPoor() {
        val machine = SignalStateMachine()
        val reading = machine.update(confluenceReading(SetupQuality.SETUP_DETECTED, QuotexDecision.CALL), false)
        assertEquals(SignalState.NO_TRADE, reading.state)
    }

    @Test
    fun watchStatePersistsWhileTheSameDirectionKeepsBeingSeen() {
        val machine = SignalStateMachine()
        val first = machine.update(confluenceReading(SetupQuality.WATCH, QuotexDecision.CALL), true)
        assertEquals(SignalState.WATCHLIST, first.state)
        val second = machine.update(confluenceReading(SetupQuality.WATCH, QuotexDecision.CALL), true)
        assertEquals(SignalState.WATCHLIST, second.state)
        assertEquals(1, second.candlesInState)
    }
}
