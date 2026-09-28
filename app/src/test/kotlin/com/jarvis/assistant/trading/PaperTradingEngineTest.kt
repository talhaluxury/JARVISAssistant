package com.jarvis.assistant.trading

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PaperTradingEngineTest {

    private lateinit var broker: DemoBrokerAdapter
    private lateinit var journal: InMemoryTradeJournal
    private lateinit var emergencyStop: EmergencyStopController
    private lateinit var engine: PaperTradingEngine
    private val settings = TradingSettings()

    @Before
    fun setUp() = runBlocking {
        broker = DemoBrokerAdapter(startingBalance = 10_000.0)
        broker.connect()
        val marketDataService = MarketDataService(broker)
        val mtfEngine = MultiTimeframeEngine(marketDataService, MarketStructureEngine())
        journal = InMemoryTradeJournal()
        emergencyStop = EmergencyStopController { settings.risk }
        engine = PaperTradingEngine(marketDataService, mtfEngine, SignalConfidenceEngine(), RiskManagementEngine(), broker, journal, emergencyStop)
    }

    private fun candle(open: Double, high: Double, low: Double, close: Double, index: Long) =
        Candle(CurrencyPair.EURUSD, Timeframe.M5, index, open, high, low, close, null, DataSource.DEMO_SIMULATED)

    private fun risingCandles(count: Int = 40): List<Candle> = (0 until count).map { i ->
        val base = 1.0 + i * 0.001
        val open = base - 0.0003
        val close = base + 0.0003
        candle(open, close + 0.0002, open - 0.0002, close, i.toLong() * 300_000L)
    }

    private fun mtfSnapshot(tf: Timeframe, regime: MarketRegime) = TimeframeStructureSnapshot(
        tf, MarketStructureSnapshot(regime, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), emptyList()
    )

    private fun goodEntryStructure(currentPrice: Double, atr: Double): MarketStructureSnapshot {
        val support = SupportResistanceLevel(currentPrice - atr * 1.5, LevelType.SUPPORT, 3, 10)
        val resistance = SupportResistanceLevel(currentPrice + atr * 5.0, LevelType.RESISTANCE, 2, 20)
        val breakout = StructureEventResult(
            StructureEvent.BREAKOUT, SupportResistanceLevel(currentPrice - atr, LevelType.RESISTANCE, 2, 39), 39, "test"
        )
        return MarketStructureSnapshot(MarketRegime.TRENDING_UP, emptyList(), emptyList(), listOf(support), listOf(resistance), listOf(breakout))
    }

    private fun tick(mid: Double, spreadAbs: Double = 0.00012) = PriceTick(
        CurrencyPair.EURUSD, mid - spreadAbs / 2, mid + spreadAbs / 2, 1_000_000L, DataSource.DEMO_SIMULATED
    )

    @Test
    fun logsWaitDecisionsWithoutOpeningAPosition() = runBlocking {
        val candles = risingCandles()
        val entry = engine.evaluate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.H4, MarketRegime.RANGING)),
            entryCandles = candles, entryStructure = goodEntryStructure(candles.last().close, 0.0010),
            latestTick = tick(candles.last().close), tickQuality = DataQuality.Valid, settings = settings
        )
        assertEquals(ExecutionStatus.NOT_ATTEMPTED, entry.executionStatus)
        assertEquals(TradeDecision.WAIT, entry.decision)
        assertEquals(0, engine.openPositionCount())
        assertEquals(1, journal.all().size)
    }

    @Test
    fun fillsAnApprovedGoodSetup() = runBlocking {
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val entry = engine.evaluate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.D1, MarketRegime.TRENDING_UP), mtfSnapshot(Timeframe.H4, MarketRegime.TRENDING_UP)),
            entryCandles = candles, entryStructure = goodEntryStructure(currentPrice, 0.0010),
            latestTick = tick(currentPrice), tickQuality = DataQuality.Valid, settings = settings
        )
        assertEquals(ExecutionStatus.FILLED, entry.executionStatus)
        assertEquals(1, engine.openPositionCount())
        assertNotNull(entry.brokerOrderId)
        assertNotNull(entry.filledPrice)
    }

    @Test
    fun blocksWhenEmergencyStopIsActive() = runBlocking {
        emergencyStop.activateEmergencyStop("test halt")
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val entry = engine.evaluate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.D1, MarketRegime.TRENDING_UP)),
            entryCandles = candles, entryStructure = goodEntryStructure(currentPrice, 0.0010),
            latestTick = tick(currentPrice), tickQuality = DataQuality.Valid, settings = settings
        )
        assertEquals(ExecutionStatus.RISK_BLOCKED, entry.executionStatus)
        assertEquals(0, engine.openPositionCount())
    }

    @Test
    fun closesPositionOnTakeProfitAndRecordsAWin() = runBlocking {
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val filled = engine.evaluate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.D1, MarketRegime.TRENDING_UP)),
            entryCandles = candles, entryStructure = goodEntryStructure(currentPrice, 0.0010),
            latestTick = tick(currentPrice), tickQuality = DataQuality.Valid, settings = settings
        )
        assertEquals(ExecutionStatus.FILLED, filled.executionStatus)
        val takeProfitPrice = filled.takeProfit!!

        val closed = engine.monitorOpenPositions(
            latestTicks = mapOf(CurrencyPair.EURUSD to tick(takeProfitPrice)),
            accountEquity = 10_000.0, dailyLossLimitPercent = settings.risk.maxDailyLossPercent
        )
        assertEquals(1, closed.size)
        assertEquals(ExecutionStatus.CLOSED, closed[0].executionStatus)
        assertTrue(closed[0].profitLoss!! > 0.0)
        assertEquals(0, engine.openPositionCount())
    }

    @Test
    fun closesPositionOnStopLossAndTriggersDailyLossLockWhenLimitReached() = runBlocking {
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val filled = engine.evaluate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.D1, MarketRegime.TRENDING_UP)),
            entryCandles = candles, entryStructure = goodEntryStructure(currentPrice, 0.0010),
            latestTick = tick(currentPrice), tickQuality = DataQuality.Valid, settings = settings
        )
        assertEquals(ExecutionStatus.FILLED, filled.executionStatus)
        val stopLossPrice = filled.stopLoss!!

        // Use a daily loss limit equal to this one trade's own risk so a single stop-out trips it.
        val closed = engine.monitorOpenPositions(
            latestTicks = mapOf(CurrencyPair.EURUSD to tick(stopLossPrice)),
            accountEquity = 10_000.0, dailyLossLimitPercent = filled.riskPercentOfEquity!!
        )
        assertEquals(1, closed.size)
        assertTrue(closed[0].profitLoss!! < 0.0)
        assertTrue(emergencyStop.state.value.dailyLossLockActive)
        assertFalse(emergencyStop.isTradingAllowed())
    }

    @Test
    fun resetForNewTradingDayClearsLossAccumulatorAndDailyLock() = runBlocking {
        emergencyStop.triggerDailyLossLock("test")
        engine.resetForNewTradingDay()
        assertEquals(0.0, engine.currentDailyLossPercent(), 1e-9)
        assertTrue(emergencyStop.isTradingAllowed())
    }
}
