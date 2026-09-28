package com.jarvis.assistant.trading

import com.jarvis.assistant.command.JarvisCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ForexBrainCommandExecutorTest {

    private lateinit var broker: DemoBrokerAdapter
    private lateinit var journal: InMemoryTradeJournal
    private lateinit var emergencyStop: EmergencyStopController
    private lateinit var settingsStore: TradingSettingsStore
    private lateinit var paperTradingEngine: PaperTradingEngine
    private lateinit var executor: ForexBrainCommandExecutor

    @Before
    fun setUp() = runBlocking {
        broker = DemoBrokerAdapter()
        broker.connect()
        val marketDataService = MarketDataService(broker)
        val mtfEngine = MultiTimeframeEngine(marketDataService, MarketStructureEngine())
        journal = InMemoryTradeJournal()
        settingsStore = TradingSettingsStore()
        emergencyStop = EmergencyStopController { settingsStore.current().risk }
        paperTradingEngine = PaperTradingEngine(marketDataService, mtfEngine, SignalConfidenceEngine(), RiskManagementEngine(), broker, journal, emergencyStop)
        executor = ForexBrainCommandExecutor(settingsStore, paperTradingEngine, journal, emergencyStop, broker)
    }

    private fun notAttemptedEntry(pair: CurrencyPair, id: String, time: Long) = TradeJournalEntry(
        id = id, timestampEpochMillis = time, pair = pair, timeframesAnalyzed = listOf(Timeframe.H1),
        decision = TradeDecision.WAIT, confidenceScore = 40, checks = emptyList(),
        entry = null, stopLoss = null, takeProfit = null, riskRewardRatio = null,
        riskPercentOfEquity = null, positionSizeLots = null, reason = "no clear trend",
        executionStatus = ExecutionStatus.NOT_ATTEMPTED
    )

    @Test
    fun rejectsUnrecognizedPairSymbol() = runBlocking {
        val response = executor.execute(JarvisCommand.AnalyzeForexPair("NOTAREALPAIR"))
        assertNotNull(response)
        assertTrue(response!!.contains("don't recognize"))
    }

    @Test
    fun analyzeRecognizedPairReturnsNonNullMentioningThePair() = runBlocking {
        val response = executor.execute(JarvisCommand.AnalyzeForexPair("EURUSD"))
        assertNotNull(response)
        assertTrue(response!!.contains("EUR/USD"))
    }

    @Test
    fun confirmWithNoPendingTradeSaysSo() = runBlocking {
        val response = executor.execute(JarvisCommand.ConfirmForexTradeExecution)
        assertEquals("There's no pending trade to confirm.", response)
    }

    @Test
    fun cancelPendingTradeAlwaysSucceedsEvenWithNothingPending() = runBlocking {
        val response = executor.execute(JarvisCommand.CancelPendingForexTrade)
        assertEquals("Trade cancelled.", response)
        // and confirming afterward still finds nothing pending
        assertEquals("There's no pending trade to confirm.", executor.execute(JarvisCommand.ConfirmForexTradeExecution))
    }

    @Test
    fun emergencyStopActivatesLockAndClearsAnyPendingTrade() = runBlocking {
        val response = executor.execute(JarvisCommand.ForexEmergencyStop)
        assertEquals("Emergency stop activated. All forex trading halted immediately.", response)
        assertTrue(emergencyStop.state.value.emergencyStopActive)
        assertFalse(emergencyStop.isTradingAllowed())
        // any trade that might have been pending is gone
        assertEquals("There's no pending trade to confirm.", executor.execute(JarvisCommand.ConfirmForexTradeExecution))
    }

    @Test
    fun pauseThenResumeRestoresTrading() = runBlocking {
        executor.execute(JarvisCommand.PauseForexTrading)
        assertFalse(emergencyStop.isTradingAllowed())
        executor.execute(JarvisCommand.ResumeForexTrading)
        assertTrue(emergencyStop.isTradingAllowed())
    }

    @Test
    fun enableLiveTradingDoesNotActuallyChangeTradingModeYet() = runBlocking {
        val response = executor.execute(JarvisCommand.EnableLiveForexTrading)
        assertNotNull(response)
        assertTrue(response!!.contains("isn't available yet", ignoreCase = true))
        assertEquals(TradingMode.DEMO, settingsStore.current().tradingMode)
    }

    @Test
    fun enableAndDisableDemoTradingUpdatesSettingsStore() = runBlocking {
        executor.execute(JarvisCommand.DisableLiveForexTrading)
        assertEquals(TradingMode.DEMO, settingsStore.current().tradingMode)
        val response = executor.execute(JarvisCommand.EnableDemoForexTrading)
        assertEquals("Demo forex trading enabled.", response)
        assertEquals(TradingMode.DEMO, settingsStore.current().tradingMode)
    }

    @Test
    fun showOpenTradesReportsNoneWhenFlat() = runBlocking {
        assertEquals("No open forex positions.", executor.execute(JarvisCommand.ShowOpenForexTrades))
    }

    @Test
    fun showPerformanceReportsNoneWhenNoClosedTrades() = runBlocking {
        assertEquals("No closed trades yet.", executor.execute(JarvisCommand.ShowForexPerformance))
    }

    @Test
    fun riskStatusReflectsEmergencyStopState() = runBlocking {
        val before = executor.execute(JarvisCommand.ShowForexRiskStatus)
        assertFalse(before!!.contains("EMERGENCY STOP"))
        emergencyStop.activateEmergencyStop("test")
        val after = executor.execute(JarvisCommand.ShowForexRiskStatus)
        assertTrue(after!!.contains("EMERGENCY STOP"))
    }

    @Test
    fun whyNoTradeReportsNothingWhenJournalIsEmpty() = runBlocking {
        val response = executor.execute(JarvisCommand.WhyNoForexTrade)
        assertNotNull(response)
        assertTrue(response!!.contains("haven't analyzed"))
    }

    @Test
    fun whyNoTradeReportsTheMostRecentNonActionableEntry() = runBlocking {
        journal.record(notAttemptedEntry(CurrencyPair.EURUSD, "e1", 1000L))
        journal.record(notAttemptedEntry(CurrencyPair.GBPUSD, "e2", 2000L)) // more recent
        val response = executor.execute(JarvisCommand.WhyNoForexTrade)
        assertNotNull(response)
        assertTrue(response!!.contains("GBP/USD"))
        assertTrue(response.contains("no clear trend"))
    }

    @Test
    fun scanWatchlistReturnsOneLinePerConfiguredPair() = runBlocking {
        val response = executor.execute(JarvisCommand.ScanForexMarket)
        assertNotNull(response)
        val lineCount = response!!.lines().size
        assertEquals(settingsStore.current().watchlist.size, lineCount)
    }
}
