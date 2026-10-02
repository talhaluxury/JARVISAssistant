package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.SetupQuality
import com.jarvis.assistant.quotex.analysis.StructureLabel
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentTest {

    private val candleMs = 15_000L

    /** Deterministic random walk with consistent OHLC and no gaps. */
    private fun walk(n: Int, seed: Long = 7L, step: Double = 0.05): List<Candle> {
        var s = seed
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 33).toDouble() / (1L shl 31).toDouble() - 0.5
        }
        val out = ArrayList<Candle>()
        var price = 100.0
        for (i in 0 until n) {
            val open = price
            val close = open + next() * step * 2
            val high = maxOf(open, close) + Math.abs(next()) * step * 0.5
            val low = minOf(open, close) - Math.abs(next()) * step * 0.5
            out.add(Candle(1_700_000_000_000L + i * candleMs, open, high, low, close))
            price = close
        }
        return out
    }

    private fun report(
        status: AgentStatus,
        dir: QuotexDecision = QuotexDecision.WAIT,
        price: Double? = 100.0,
        atr: Double? = 0.1,
        vol: VolatilityState = VolatilityState.NORMAL,
        reasons: List<String> = listOf("Test reason.")
    ) = AgentReport(
        status = status, direction = dir, quality = SetupQuality.SETUP_DETECTED, dataQuality = DataQuality.EXCELLENT,
        dataSummary = "ok", trend = TrendState.RANGE, structure = StructureLabel.UNCLEAR, volatility = vol,
        regime = MarketRegime.RANGE, session = MarketSession.ASIAN, newsRisk = NewsRisk.UNAVAILABLE,
        conditionsMet = 6, conditionsTotal = 8, strategies = listOf("Pullback"), checks = emptyList(), reasons = reasons,
        edge = null, timeMs = 0L, lastPrice = price, atr = atr
    )

    private fun entry(id: String = "a-1") = JournalEntry(
        id = id, timestampMs = 1000L, asset = "EUR/USD OTC", timeframeSeconds = 15, regime = "RANGE",
        strategies = listOf("Pullback", "EMA Structure"), direction = "CALL", quality = "SETUP_DETECTED",
        confidenceBucket = "70-85%", conditionsMet = 6, conditionsTotal = 8, featuresSummary = "a=b;c=d",
        entryPrice = 1.2345, expiryMs = 61_000L, strategyVersion = "2.0.0", analysisVersion = "2.0.0",
        indicatorSettings = "ema=9", session = "ASIAN", reasonText = "line1\nline2\twith tab and back\\slash"
    )

    // ---- data validation ---------------------------------------------------------------------------------

    @Test
    fun cleanDataIsExcellent() {
        val r = DataValidator(candleMs).validate(walk(300))
        assertEquals(DataQuality.EXCELLENT, r.quality)
        assertTrue(r.tradable)
    }

    @Test
    fun tooFewCandlesIsPoor() {
        val r = DataValidator(candleMs).validate(walk(50))
        assertEquals(DataQuality.POOR, r.quality)
        assertTrue(r.issues.any { it.code == "TOO_FEW" })
        assertFalse(r.tradable)
    }

    @Test
    fun invalidOhlcIsPoor() {
        val c = walk(300).toMutableList()
        val bad = c[100]
        c[100] = bad.copy(high = bad.low - 1.0)
        val r = DataValidator(candleMs).validate(c)
        assertEquals(DataQuality.POOR, r.quality)
        assertTrue(r.issues.any { it.code == "BAD_OHLC" })
    }

    @Test
    fun duplicateTimestampIsReported() {
        val c = walk(300).toMutableList()
        c[100] = c[100].copy(openTimeMs = c[99].openTimeMs)
        val r = DataValidator(candleMs).validate(c)
        assertTrue(r.issues.any { it.code == "DUPLICATE" })
        assertNotEquals(DataQuality.EXCELLENT, r.quality)
    }

    @Test
    fun flatPriceIsStale() {
        val c = walk(300).toMutableList()
        val p = c[293].close
        for (i in 294 until 300) c[i] = c[i].copy(open = p, high = p, low = p, close = p)
        val r = DataValidator(candleMs).validate(c)
        assertTrue(r.issues.any { it.code == "STALE_PRICE" })
        assertEquals(DataQuality.POOR, r.quality)
    }

    @Test
    fun lowOcrConfidenceIsPoor() {
        val r = DataValidator(candleMs).validate(walk(300), ocrConfidence = 0.3)
        assertEquals(DataQuality.POOR, r.quality)
        assertTrue(r.issues.any { it.code == "LOW_OCR" })
    }

    @Test
    fun staleFeedIsPoor() {
        val c = walk(300)
        val r = DataValidator(candleMs).validate(c, nowMs = c.last().openTimeMs + 10 * candleMs)
        assertTrue(r.issues.any { it.code == "STALE_FEED" })
        assertEquals(DataQuality.POOR, r.quality)
    }

    // ---- price action -------------------------------------------------------------------------------------

    @Test
    fun hammerIsDetected() {
        val base = walk(10).toMutableList()
        val t = base.last().openTimeMs + candleMs
        base.add(Candle(t, 100.0, 100.12, 99.5, 100.1))
        val found = PriceActionEngine.detect(PriceSeries(base))
        assertTrue(found.any { it.pattern == CandlePattern.HAMMER && it.bias == PatternBias.BULLISH })
    }

    @Test
    fun bullishEngulfingIsDetected() {
        val base = walk(10).toMutableList()
        val t = base.last().openTimeMs
        base.add(Candle(t + candleMs, 100.0, 100.05, 99.75, 99.8))
        base.add(Candle(t + 2 * candleMs, 99.7, 100.15, 99.65, 100.1))
        val found = PriceActionEngine.detect(PriceSeries(base))
        assertTrue(found.any { it.pattern == CandlePattern.BULLISH_ENGULFING })
    }

    @Test
    fun tooShortSeriesHasNoPatterns() {
        assertTrue(PriceActionEngine.detect(PriceSeries(walk(2))).isEmpty())
    }

    // ---- resampling / multi-timeframe ---------------------------------------------------------------------

    @Test
    fun resampleBuildsOnlyCompleteBuckets() {
        val c = (0 until 14).map { Candle(it * 1000L, 10.0 + it, 11.0 + it, 9.0 + it, 10.5 + it) }
        val out = CandleResampler.resample(c, 4, 1000L)
        assertEquals(3, out.size)
        assertEquals(c[0].open, out[0].open, 0.0)
        assertEquals(c[3].close, out[0].close, 0.0)
        assertEquals(c[3].high, out[0].high, 0.0)
        assertEquals(c[0].low, out[0].low, 0.0)
    }

    @Test
    fun mtfStrategyAbstainsWithoutEnoughHistory() {
        val s = PriceSeries(walk(100))
        val r = MultiTimeframeConfluenceStrategy().evaluate(
            s, MarketStructure.trendLabel(s), StructureLabel.UNCLEAR, MarketStructure.volatilityLabel(s), MarketStructure.swings(s)
        )
        assertEquals(QuotexDecision.WAIT, r.direction)
    }

    // ---- strategies ----------------------------------------------------------------------------------------

    @Test
    fun everyStrategyIsSafeAtEveryHistoryLength() {
        val library = fullStrategyLibrary()
        assertEquals(10, library.size)
        for (n in listOf(10, 30, 60, 130, 250, 400)) {
            val s = PriceSeries(walk(n, seed = n.toLong() + 3))
            val trend = MarketStructure.trendLabel(s)
            val structure = MarketStructure.structureLabel(MarketStructure.swings(s))
            val vol = MarketStructure.volatilityLabel(s)
            val swings = MarketStructure.swings(s)
            for (strategy in library) {
                val r = strategy.evaluate(s, trend, structure, vol, swings)
                if (r.direction == QuotexDecision.WAIT) assertTrue(r.conditions.isEmpty()) else assertTrue(r.conditions.isNotEmpty())
            }
        }
    }

    // ---- regime / session / news ------------------------------------------------------------------------------

    @Test
    fun regimeGateSeparatesTrendFromReversal() {
        assertFalse(RegimeGate.allows("Trend Continuation", MarketRegime.RANGE))
        assertFalse(RegimeGate.allows("Support/Resistance Reversal", MarketRegime.STRONG_UPTREND))
        assertTrue(RegimeGate.allows("Pullback", MarketRegime.WEAK_UPTREND))
        for (name in fullStrategyLibrary().map { it.name }) assertFalse(RegimeGate.allows(name, MarketRegime.UNSTABLE))
    }

    @Test
    fun sessionsByUtcHour() {
        val h = 3_600_000L
        assertEquals(MarketSession.ASIAN, SessionClassifier.at(0))
        assertEquals(MarketSession.LONDON, SessionClassifier.at(8 * h))
        assertEquals(MarketSession.LONDON_NY_OVERLAP, SessionClassifier.at(13 * h))
        assertEquals(MarketSession.NEW_YORK, SessionClassifier.at(18 * h))
        assertEquals(MarketSession.OFF_HOURS, SessionClassifier.at(22 * h))
    }

    @Test
    fun newsRiskNeverInventsData() {
        assertEquals(NewsRisk.UNAVAILABLE, NewsRiskFilter(null).assess(0L).first)
        val near = NewsRiskFilter(listOf(EconomicEvent(10 * 60_000L, "NFP", EventImpact.HIGH)))
        assertEquals(NewsRisk.HIGH, near.assess(0L).first)
        val far = NewsRiskFilter(listOf(EconomicEvent(3 * 3_600_000L, "NFP", EventImpact.HIGH)))
        assertEquals(NewsRisk.LOW, far.assess(0L).first)
    }

    // ---- clock ---------------------------------------------------------------------------------------------------

    @Test
    fun candleClockMath() {
        assertEquals(60_000L, CandleClock.remainingMs(0L, 60_000L))
        assertEquals(15_000L, CandleClock.remainingMs(45_000L, 60_000L))
        assertEquals("02:15", CandleClock.format(135_000L))
        assertEquals("5M", CandleClock.label(300))
        assertEquals("15S", CandleClock.label(15))
        assertEquals("1H", CandleClock.label(3600))
    }

    // ---- edge test / calibration -----------------------------------------------------------------------------

    @Test
    fun edgeNeedsSampleSizeAndSignificance() {
        assertFalse(EdgeTest.check(null, 0.54).verified)
        assertFalse(EdgeTest.check(HistoricalEvidence(50, 40), 0.54).verified)
        assertTrue(EdgeTest.check(HistoricalEvidence(200, 150), 0.54).verified)
        assertFalse(EdgeTest.check(HistoricalEvidence(200, 108), 0.54).verified)
    }

    // ---- journal -------------------------------------------------------------------------------------------------

    @Test
    fun journalCodecRoundTrips() {
        val e = entry()
        assertEquals(e, JournalCodec.decode(JournalCodec.encode(e)))
        val resolved = e.copy(outcome = JournalOutcome.WIN, exitPrice = 1.25, screenshotRef = "shot.png")
        assertEquals(resolved, JournalCodec.decode(JournalCodec.encode(resolved)))
    }

    @Test
    fun journalOutcomeIsWrittenOnlyOnce() {
        val j = QuotexJournal()
        j.record(entry())
        assertTrue(j.resolve("a-1", JournalOutcome.LOSS, 1.2))
        assertFalse(j.resolve("a-1", JournalOutcome.WIN, 1.3))
        assertEquals(JournalOutcome.LOSS, j.find("a-1")?.outcome)
        assertTrue(j.whyFailed(j.find("a-1")!!).contains("EUR/USD OTC"))
    }

    @Test
    fun journalIgnoresDuplicateIdsAndListsNewestFirst() {
        val j = QuotexJournal()
        j.record(entry("a-1"))
        j.record(entry("a-1"))
        j.record(entry("a-2"))
        assertEquals(2, j.summary().total)
        assertEquals("a-2", j.last(1).first().id)
    }

    @Test
    fun fileStorePersistsEntries() {
        val f = File.createTempFile("quotex_journal", ".tsv")
        try {
            val a = QuotexJournal(FileJournalStore(f))
            a.record(entry("x-1"))
            a.resolve("x-1", JournalOutcome.WIN, 1.3)
            val b = QuotexJournal(FileJournalStore(f))
            assertEquals(JournalOutcome.WIN, b.find("x-1")?.outcome)
        } finally {
            f.delete()
        }
    }

    // ---- signal lifecycle ---------------------------------------------------------------------------------------

    @Test
    fun setupExpiresAfterItsWindow() {
        val l = SignalLifecycle(validityMs = 60_000L)
        val setup = report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL)
        assertEquals(LifecycleState.ACTIVE, l.update(setup, 0L).state)
        val mid = l.update(setup, 30_000L)
        assertEquals(LifecycleState.ACTIVE, mid.state)
        assertEquals(30L, mid.remainingSeconds)
        assertEquals(LifecycleState.EXPIRED, l.update(setup, 61_000L).state)
        assertEquals(LifecycleState.NONE, l.update(report(AgentStatus.WAIT), 62_000L).state)
    }

    @Test
    fun opposingSignalInvalidates() {
        val l = SignalLifecycle(60_000L)
        l.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL), 0L)
        assertEquals(LifecycleState.INVALIDATED, l.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.PUT), 1_000L).state)
    }

    @Test
    fun priceRunawayAndVolatilityInvalidate() {
        val a = SignalLifecycle(60_000L)
        a.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL, price = 100.0, atr = 0.1), 0L)
        assertEquals(
            LifecycleState.INVALIDATED,
            a.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL, price = 100.5, atr = 0.1), 1_000L).state
        )
        val b = SignalLifecycle(60_000L)
        b.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL), 0L)
        assertEquals(
            LifecycleState.INVALIDATED,
            b.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL, vol = VolatilityState.HIGH), 1_000L).state
        )
    }

    @Test
    fun noTradeInvalidatesActiveSetup() {
        val l = SignalLifecycle(60_000L)
        l.update(report(AgentStatus.SETUP_DETECTED, QuotexDecision.CALL), 0L)
        assertEquals(LifecycleState.INVALIDATED, l.update(report(AgentStatus.NO_TRADE), 1_000L).state)
    }

    // ---- explanation ----------------------------------------------------------------------------------------------

    @Test
    fun explanationNeverPromisesAnOutcome() {
        val text = ExplanationEngine.explain(report(AgentStatus.WAIT))
        assertTrue(text.contains("Result: WAIT"))
        assertTrue(text.contains("not a guaranteed outcome"))
        assertTrue(ExplanationEngine.speech(report(AgentStatus.NO_TRADE)).startsWith("No trade"))
        assertTrue(ExplanationEngine.card(report(AgentStatus.WAIT), "EUR/USD", 300, 0L, "CONDITIONS CHANGED").contains("STATUS:\nWAIT"))
    }

    // ---- analyzer / runtime / backtest --------------------------------------------------------------------------------

    @Test
    fun analyzerReportsInsufficientData() {
        val r = AgentAnalyzer().analyze(walk(50))
        assertEquals(AgentStatus.DATA_UNCERTAIN, r.status)
        assertTrue(r.headlineReason.contains("INSUFFICIENT DATA"))
        assertEquals(QuotexDecision.WAIT, r.direction)
    }

    @Test
    fun analyzerIsDeterministicAndNeverSetsUpWithoutVerifiedEdge() {
        val c = walk(400)
        val a = AgentAnalyzer().analyze(c)
        val b = AgentAnalyzer().analyze(c)
        assertEquals(a.status, b.status)
        assertEquals(a.reasons, b.reasons)
        assertNotEquals(AgentStatus.SETUP_DETECTED, a.status)
        assertEquals(AnalysisVersions.STRATEGY_VERSION, a.strategyVersion)
    }

    @Test
    fun riskPauseForcesNoTrade() {
        val r = AgentAnalyzer().analyze(walk(400), riskPausedReason = "Daily loss limit reached")
        assertEquals(AgentStatus.NO_TRADE, r.status)
        assertTrue(r.headlineReason.contains("TRADING PAUSED"))
    }

    @Test
    fun highNewsRiskForcesNoTrade() {
        val c = walk(400)
        val filter = NewsRiskFilter(listOf(EconomicEvent(c.last().openTimeMs + 60_000L, "FOMC", EventImpact.HIGH)))
        val r = AgentAnalyzer(AgentConfig(), filter).analyze(c)
        assertEquals(AgentStatus.NO_TRADE, r.status)
    }

    @Test
    fun simulationModeNeverShowsLiveSetup() {
        val c = walk(400)
        val runtime = AgentRuntime(AgentConfig())
        var last: AgentSnapshot? = null
        for (n in 150..c.size step 5) {
            last = runtime.onCandleClosed(c.subList(0, n), c[n - 1].openTimeMs + candleMs, "TEST")
        }
        assertNotNull(last)
        assertEquals(AgentMode.SIMULATION, last!!.mode)
        assertNotEquals(AgentStatus.SETUP_DETECTED, last.report.status)
    }

    @Test
    fun backtestSegmentsPartitionTheSignals() {
        val c = walk(700, seed = 11L)
        val report = AgentBacktester(AgentConfig()).run(c)
        assertTrue(report.evaluations > 0)
        val total = report.train.setups + report.validation.setups + report.outOfSample.setups
        assertEquals(report.signals.size, total)
        assertEquals(
            report.evaluations,
            report.waitCount + report.noTradeCount + report.dataUncertainCount + report.watchCount + report.signals.size
        )
        assertTrue(report.signals.all { it.index >= 149 })
        assertFalse(report.summary.isEmpty())
        // A random walk must not be reported as a confirmed edge unless the numbers genuinely say so.
        if (report.edgeConfirmedOutOfSample) assertTrue((report.outOfSample.zVsBreakEven ?: 0.0) >= 2.33)
    }

    @Test
    fun rollingWindowsUseOnlyResolvedSetups() {
        val bt = AgentBacktester(AgentConfig())
        val sig = (0 until 10).map {
            BacktestSignal(it, QuotexDecision.CALL, listOf("x"), MarketRegime.RANGE, MarketSession.ASIAN, "b", if (it % 2 == 0) true else false)
        }
        val rolled = bt.rolling(sig, window = 4, stride = 2)
        assertEquals(4, rolled.size)
        assertTrue(rolled.all { it == 0.5 })
    }
}
