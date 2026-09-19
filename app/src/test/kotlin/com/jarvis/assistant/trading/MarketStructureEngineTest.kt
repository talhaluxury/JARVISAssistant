package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test

class MarketStructureEngineTest {

    private val engine = MarketStructureEngine(swingLookback = 2, trendSwingWindow = 4, minTouchesForLevel = 2)

    private fun candle(open: Double, high: Double, low: Double, close: Double, index: Long) =
        Candle(CurrencyPair.EURUSD, Timeframe.M15, index, open, high, low, close, null, DataSource.DEMO_SIMULATED)

    private fun flat(index: Long, level: Double) = candle(level, level + 0.001, level - 0.001, level, index)

    // --- swing detection ---

    @Test
    fun detectsAnObviousSwingHighAtTheCenter() {
        val candles = listOf(
            flat(0, 1.00), flat(1, 1.00),
            candle(1.00, 1.05, 0.99, 1.00, 2), // peak
            flat(3, 1.00), flat(4, 1.00)
        )
        val swings = engine.detectSwingPoints(candles)
        assertTrue(swings.any { it.index == 2 && it.type == SwingType.HIGH && it.price == 1.05 })
        assertFalse(swings.any { it.type == SwingType.HIGH && it.index != 2 })
    }

    @Test
    fun detectsAnObviousSwingLowAtTheCenter() {
        val candles = listOf(
            flat(0, 1.00), flat(1, 1.00),
            candle(1.00, 1.01, 0.90, 1.00, 2), // trough
            flat(3, 1.00), flat(4, 1.00)
        )
        val swings = engine.detectSwingPoints(candles)
        assertTrue(swings.any { it.index == 2 && it.type == SwingType.LOW && it.price == 0.90 })
    }

    // --- labeling ---

    @Test
    fun labelsSwingsRelativeToLastSameTypeSwing() {
        val swings = listOf(
            SwingPoint(0, 1.00, SwingType.LOW, 0),
            SwingPoint(1, 1.10, SwingType.HIGH, 0),
            SwingPoint(2, 1.05, SwingType.LOW, 0),  // higher than 1.00 -> HIGHER_LOW
            SwingPoint(3, 1.15, SwingType.HIGH, 0), // higher than 1.10 -> HIGHER_HIGH
            SwingPoint(4, 1.02, SwingType.LOW, 0)   // lower than 1.05 -> LOWER_LOW
        )
        val labeled = engine.labelSwings(swings)
        assertNull(labeled[0].label) // first LOW, nothing to compare
        assertNull(labeled[1].label) // first HIGH, nothing to compare
        assertEquals(StructureLabel.HIGHER_LOW, labeled[2].label)
        assertEquals(StructureLabel.HIGHER_HIGH, labeled[3].label)
        assertEquals(StructureLabel.LOWER_LOW, labeled[4].label)
    }

    // --- regime classification ---

    @Test
    fun classifiesUptrendWhenAllRecentSwingsAreBullish() {
        val labeled = listOf(
            LabeledSwing(SwingPoint(0, 1.0, SwingType.LOW, 0), StructureLabel.HIGHER_LOW),
            LabeledSwing(SwingPoint(1, 1.1, SwingType.HIGH, 0), StructureLabel.HIGHER_HIGH),
            LabeledSwing(SwingPoint(2, 1.05, SwingType.LOW, 0), StructureLabel.HIGHER_LOW),
            LabeledSwing(SwingPoint(3, 1.15, SwingType.HIGH, 0), StructureLabel.HIGHER_HIGH)
        )
        assertEquals(MarketRegime.TRENDING_UP, engine.classifyRegime(labeled))
    }

    @Test
    fun classifiesRangingWhenSwingsAreMixed() {
        val labeled = listOf(
            LabeledSwing(SwingPoint(0, 1.0, SwingType.LOW, 0), StructureLabel.HIGHER_LOW),
            LabeledSwing(SwingPoint(1, 1.1, SwingType.HIGH, 0), StructureLabel.LOWER_HIGH),
            LabeledSwing(SwingPoint(2, 0.95, SwingType.LOW, 0), StructureLabel.LOWER_LOW),
            LabeledSwing(SwingPoint(3, 1.12, SwingType.HIGH, 0), StructureLabel.HIGHER_HIGH)
        )
        assertEquals(MarketRegime.RANGING, engine.classifyRegime(labeled))
    }

    @Test
    fun classifiesUncertainWhenTooFewLabeledSwings() {
        val labeled = listOf(
            LabeledSwing(SwingPoint(0, 1.0, SwingType.LOW, 0), null),
            LabeledSwing(SwingPoint(1, 1.1, SwingType.HIGH, 0), StructureLabel.HIGHER_HIGH)
        )
        assertEquals(MarketRegime.UNCERTAIN, engine.classifyRegime(labeled))
    }

    // --- support/resistance clustering ---

    @Test
    fun clustersNearbySwingsIntoLevelsAndClassifiesSideRelativeToCurrentPrice() {
        val swings = listOf(
            SwingPoint(0, 1.099, SwingType.LOW, 0),
            SwingPoint(1, 1.101, SwingType.LOW, 0),
            SwingPoint(2, 1.100, SwingType.LOW, 0),
            SwingPoint(3, 1.199, SwingType.HIGH, 0),
            SwingPoint(4, 1.201, SwingType.HIGH, 0)
        )
        val (support, resistance) = engine.findSupportResistance(swings, currentPrice = 1.15, tolerance = 0.003)
        assertEquals(1, support.size)
        assertEquals(3, support[0].touches)
        assertEquals(1, resistance.size)
        assertEquals(2, resistance[0].touches)
    }

    @Test
    fun discardsLevelsBelowMinimumTouchCount() {
        val swings = listOf(SwingPoint(0, 1.10, SwingType.LOW, 0)) // only 1 touch, min is 2
        val (support, resistance) = engine.findSupportResistance(swings, currentPrice = 1.20, tolerance = 0.01)
        assertTrue(support.isEmpty())
        assertTrue(resistance.isEmpty())
    }

    // --- breakout / false breakout / liquidity sweep / rejection ---

    @Test
    fun detectsConfirmedBreakoutOnCloseThroughResistance() {
        val level = SupportResistanceLevel(1.10, LevelType.RESISTANCE, touches = 3, lastTouchIndex = 5)
        val candles = listOf(
            candle(1.08, 1.095, 1.075, 1.09, 0),  // prior: closes below resistance
            candle(1.09, 1.115, 1.088, 1.112, 1)  // current: closes above resistance
        )
        val events = engine.detectEvents(candles, listOf(level))
        assertEquals(1, events.size)
        assertEquals(StructureEvent.BREAKOUT, events[0].event)
    }

    @Test
    fun detectsFalseBreakoutWhenWickPiercesButCloseSnapsBack() {
        val level = SupportResistanceLevel(1.10, LevelType.RESISTANCE, touches = 3, lastTouchIndex = 5)
        val candles = listOf(
            candle(1.08, 1.095, 1.075, 1.09, 0),
            candle(1.09, 1.115, 1.085, 1.095, 1) // wicks above 1.10 but closes back below
        )
        val events = engine.detectEvents(candles, listOf(level))
        assertEquals(1, events.size)
        assertEquals(StructureEvent.FALSE_BREAKOUT, events[0].event)
    }

    @Test
    fun tagsLiquiditySweepWhenLevelHasOnlyOneTouch() {
        val level = SupportResistanceLevel(1.10, LevelType.RESISTANCE, touches = 1, lastTouchIndex = 5)
        val candles = listOf(
            candle(1.08, 1.095, 1.075, 1.09, 0),
            candle(1.09, 1.115, 1.085, 1.095, 1)
        )
        val events = engine.detectEvents(candles, listOf(level))
        assertEquals(StructureEvent.LIQUIDITY_SWEEP, events[0].event)
    }

    @Test
    fun detectsResistanceRejectionOnLongUpperWickWithoutBreach() {
        val level = SupportResistanceLevel(1.10, LevelType.RESISTANCE, touches = 3, lastTouchIndex = 5)
        val candles = listOf(
            candle(1.08, 1.085, 1.075, 1.082, 0),
            // long upper wick approaching but not touching 1.10, tiny body, closes well below
            candle(1.085, 1.099, 1.083, 1.087, 1)
        )
        val events = engine.detectEvents(candles, listOf(level))
        assertEquals(1, events.size)
        assertEquals(StructureEvent.RESISTANCE_REJECTION, events[0].event)
    }

    @Test
    fun noEventWhenPriorCandleAlreadyOnFarSideOfLevel() {
        // prior close already above resistance -> not a fresh approach, should not fire again
        val level = SupportResistanceLevel(1.10, LevelType.RESISTANCE, touches = 3, lastTouchIndex = 5)
        val candles = listOf(
            candle(1.11, 1.13, 1.105, 1.12, 0),
            candle(1.12, 1.14, 1.115, 1.13, 1)
        )
        val events = engine.detectEvents(candles, listOf(level))
        assertTrue(events.isEmpty())
    }
}
