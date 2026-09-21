package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.CandleBuilder
import com.jarvis.assistant.quotex.analysis.CandleRuns
import com.jarvis.assistant.quotex.analysis.Indicators
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.QuotexBacktestEngine
import com.jarvis.assistant.quotex.analysis.QuotexEngine
import com.jarvis.assistant.quotex.analysis.QuotexModel
import com.jarvis.assistant.quotex.analysis.QuotexPrediction
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.domain.QuotexConfig
import com.jarvis.assistant.quotex.ocr.AxisLabel
import com.jarvis.assistant.quotex.ocr.GridPriceFinder
import com.jarvis.assistant.quotex.ocr.QuotexCandleCsv
import com.jarvis.assistant.quotex.ocr.QuotexScreenParser
import com.jarvis.assistant.quotex.voice.QuotexNarrator
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.analysis.EdgeAssessment
import com.jarvis.assistant.wingo.analysis.ModelOutput
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class QuotexTest {

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun candlesFromCloses(closes: List<Double>, candleMs: Long = 15_000L, start: Double = 1.0): List<Candle> {
        var previous = start
        return closes.mapIndexed { i, close ->
            val c = Candle(1_700_000_000_000L + i * candleMs, previous, maxOf(previous, close), minOf(previous, close), close)
            previous = close
            c
        }
    }

    private fun randomWalk(n: Int, seed: Long): List<Candle> {
        val random = Random(seed)
        var price = 1.0
        val closes = List(n) {
            price += random.nextGaussian() * 0.0002
            if (price < 0.1) price = 0.1
            price
        }
        return candlesFromCloses(closes)
    }

    private fun pattern(pat: String, n: Int): List<Candle> {
        var price = 100.0
        val closes = List(n) {
            price += if (pat[it % pat.length] == 'U') 0.001 else -0.001
            price
        }
        return candlesFromCloses(closes, start = 100.0)
    }

    // ---- indicators ------------------------------------------------------------------------------------------

    @Test
    fun indicatorsBehaveOnSimpleInputs() {
        val flat = DoubleArray(30) { 1.0 }
        assertEquals(1.0, Indicators.ema(flat, 9)[29], 1e-12)
        val rising = DoubleArray(20) { 1.0 + it }
        val rsi = Indicators.rsi(rising, 14)
        assertTrue(rsi[13].isNaN())
        assertEquals(100.0, rsi[14], 1e-9)
        assertEquals(50.0, Indicators.rsi(flat, 14)[20], 1e-9)
        val b = Indicators.percentB(flat, 20, 2.0)
        assertTrue(b[18].isNaN())
        assertEquals(0.5, b[19], 1e-9)
    }

    // ---- candle building ---------------------------------------------------------------------------------------

    @Test
    fun ticksAreAggregatedIntoClosedCandles() {
        val builder = CandleBuilder(15)
        assertNull(builder.add(0L, 1.00))
        assertNull(builder.add(5_000L, 1.20))
        assertNull(builder.add(10_000L, 0.90))
        val first = builder.add(15_000L, 1.10)
        assertNotNull(first)
        assertEquals(0L, first!!.openTimeMs)
        assertEquals(1.00, first.open, 1e-12)
        assertEquals(1.20, first.high, 1e-12)
        assertEquals(0.90, first.low, 1e-12)
        assertEquals(0.90, first.close, 1e-12)
        val second = builder.add(30_000L, 1.05)
        assertEquals(15_000L, second!!.openTimeMs)
        assertEquals(1.10, second.open, 1e-12)
    }

    @Test
    fun contiguousTailAndGapsFollowMissingCandles() {
        val ms = 15_000L
        val times = listOf(0L, 1L, 2L, 20L, 21L).map { it * ms }
        val candles = times.map { Candle(it, 1.0, 1.0, 1.0, 1.0) }
        assertEquals(2, CandleRuns.contiguousTail(candles, ms).size)
        assertEquals(1, CandleRuns.countGaps(candles, ms))
        assertEquals(3, CandleRuns.contiguousTail(candles.take(3), ms).size)
    }

    // ---- reading the chart ----------------------------------------------------------------------------------------

    private fun axis(vararg values: Double): List<AxisLabel> = values.mapIndexed { i, v -> AxisLabel(v, 500f - i * 100f) }

    @Test
    fun livePriceIsTheOneLabelThatIsOffTheGrid() {
        val grid = listOf(1.0820, 1.0822, 1.0824, 1.0826, 1.0828).mapIndexed { i, v -> AxisLabel(v, 500f - i * 100f) }
        val live = AxisLabel(1.08253, 235f) // 2.65 grid steps above the bottom label
        assertEquals(1.08253, GridPriceFinder.find(grid + live, 40f)!!, 1e-12)
    }

    @Test
    fun ambiguousOrInconsistentAxisReadingsGiveNoPrice() {
        val grid = listOf(1.0820, 1.0822, 1.0824, 1.0826, 1.0828).mapIndexed { i, v -> AxisLabel(v, 500f - i * 100f) }
        // two off-grid labels among a longer grid
        val longGrid = (0 until 8).map { AxisLabel(1.0820 + it * 0.0002, 500f - it * 50f) }
        assertNull(GridPriceFinder.find(longGrid + AxisLabel(1.08253, 367f) + AxisLabel(1.08311, 245f), 40f))
        // too few labels
        assertNull(GridPriceFinder.find(grid.take(3) + AxisLabel(1.08253, 235f), 40f))
        // off-grid label at the wrong height
        assertNull(GridPriceFinder.find(grid + AxisLabel(1.08253, 450f), 40f))
        // nothing off the grid at all
        assertNull(GridPriceFinder.find(grid, 40f))
    }

    private fun word(text: String, left: Int, centerY: Int) = OcrLine(text, left, centerY - 15, left + 100, centerY + 15, 0.95f)

    @Test
    fun parserReadsAssetAndLivePriceAndIgnoresNumbersOutsideTheAxis() {
        val lines = ArrayList<OcrLine>()
        lines.add(word("EUR/USD", 20, 45))
        lines.add(word("OTC", 300, 45))
        val gridValues = listOf("1.08200", "1.08220", "1.08240", "1.08260", "1.08280")
        gridValues.forEachIndexed { i, text -> lines.add(word(text, 900, 500 - i * 100)) }
        lines.add(word("1.08253", 900, 235))
        lines.add(word("1.09999", 100, 300)) // not on the right-hand axis
        val reading = QuotexScreenParser().parse(lines, 1080, 1600)
        assertEquals("EURUSD_OTC", reading.asset)
        assertEquals(1.08253, reading.price!!, 1e-12)
    }

    // ---- engine: no look-ahead, honest gating ------------------------------------------------------------------------

    private class SpyModel : QuotexModel {
        override val name: String = "Spy"
        val seen = ArrayList<List<Double>>()
        override fun predict(series: PriceSeries, horizon: Int): ModelOutput {
            seen.add(series.closes.toList())
            return ModelOutput(name, 0.5, series.size, "spy")
        }
    }

    private class FixedModel(private val p: Double) : QuotexModel {
        override val name: String = "Fixed"
        override fun predict(series: PriceSeries, horizon: Int): ModelOutput = ModelOutput(name, p, series.size, "fixed")
    }

    @Test
    fun predictionAtCandleIOnlySeesCandlesUpToI() {
        val candles = candlesFromCloses(List(60) { 1.0 + it * 0.001 + (it % 3) * 0.0004 })
        val spy = SpyModel()
        val config = QuotexConfig(minCandlesForSignal = 20, expiryCandles = 2)
        QuotexEngine(config, listOf(spy)).walkForward(candles)
        assertEquals(41, spy.seen.size) // indexes 19..59
        val closes = candles.map { it.close }
        spy.seen.forEachIndexed { k, visible ->
            assertEquals(20 + k, visible.size)
            assertEquals(closes.subList(0, 20 + k), visible)
        }
    }

    @Test
    fun outcomesAreResolvedExactlyOneExpiryLater() {
        val candles = candlesFromCloses(List(60) { 1.0 + it * 0.001 })
        val resolved = ArrayList<Int>()
        QuotexEngine(QuotexConfig(minCandlesForSignal = 20, expiryCandles = 2), listOf(SpyModel())).walkForward(candles) { idx, _, _ ->
            resolved.add(idx)
        }
        assertEquals((0 until 58).toList(), resolved)
    }

    @Test
    fun edgeRecordCountsOnlyNonOverlappingCalls() {
        val candles = candlesFromCloses(List(100) { 1.0 + it * 0.001 }) // always rising
        val engine = QuotexEngine(QuotexConfig(minCandlesForSignal = 20, expiryCandles = 4), listOf(FixedModel(0.9)))
        engine.walkForward(candles)
        val edge = engine.edgeAssessment()
        assertEquals(19, edge.samples) // idx 20, 24, ..., 92
        assertEquals(19, edge.hits)
    }

    @Test
    fun tooLittleHistoryWaits() {
        val engine = QuotexEngine()
        val p = engine.predict(PriceSeries(randomWalk(50, 1)))
        assertEquals(QuotexDecision.WAIT, p.decision)
        assertTrue(p.waitReason!!.contains("Insufficient price history"))
    }

    @Test
    fun aStrongLeanWaitsUntilAnEdgeIsVerified() {
        val series = PriceSeries(randomWalk(200, 2))
        val gated = QuotexEngine(QuotexConfig(), listOf(FixedModel(0.9))).predict(series)
        assertEquals(QuotexDecision.CALL, gated.lean)
        assertEquals(QuotexDecision.WAIT, gated.decision)
        assertTrue(gated.isCandidate)
        assertTrue(gated.waitReason!!.contains("no statistically verified edge"))
        val open = QuotexEngine(QuotexConfig(requireVerifiedEdge = false), listOf(FixedModel(0.9))).predict(series)
        assertEquals(QuotexDecision.CALL, open.decision)
        assertEquals(Signal.HIGH, open.signal)
    }

    @Test
    fun aRepeatingPricePatternIsFoundAndSignalled() {
        val candles = pattern("UUDD", 1200)
        val engine = QuotexEngine(QuotexConfig(expiryCandles = 1))
        var signals = 0
        var correct = 0
        engine.walkForward(candles) { idx, prediction, higher ->
            if (idx >= 900 && prediction.isSignal && higher != null) {
                signals++
                if ((prediction.lean == QuotexDecision.CALL) == higher) correct++
            }
        }
        assertTrue("expected signals once the pattern proved itself, got $signals", signals > 100)
        assertTrue(correct.toDouble() / signals >= 0.95)
    }

    @Test
    fun randomWalkGivesNoFalseSenseOfAccuracy() {
        val report = QuotexBacktestEngine().run(randomWalk(1500, 5))
        val accuracy = report.independentCalls.accuracy!!
        assertTrue("independent accuracy on a random walk should be near 50%, was $accuracy", accuracy in 0.38..0.62)
        assertFalse(report.verdict.contains("above break-even"))
    }

    @Test
    fun reportContainsBreakEvenAndProfitAndVerdict() {
        val text = QuotexBacktestEngine(QuotexConfig(payout = 0.85)).run(randomWalk(400, 9)).toText()
        for (needle in listOf("QUOTEX BACKTEST", "Break-even accuracy", "Simulated P/L", "Predictions:", "Verdict:")) {
            assertTrue("missing '$needle'", text.contains(needle))
        }
        assertEquals(1.0 / 1.85, QuotexConfig(payout = 0.85).breakEvenAccuracy, 1e-12)
    }

    @Test
    fun invalidConfigIsRejected() {
        try {
            QuotexConfig(candleSeconds = 3)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            QuotexConfig(payout = 0.1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- backup format -----------------------------------------------------------------------------------------------------

    @Test
    fun candleCsvRoundTripsAndRejectsBadRows() {
        val candles = candlesFromCloses(listOf(1.0823, 1.0825, 1.0821))
        val csv = QuotexCandleCsv.toCsv(mapOf("EURUSD_OTC" to candles))
        val parsed = QuotexCandleCsv.parse(csv)
        assertEquals(candles, parsed.byAsset["EURUSD_OTC"])
        assertEquals(0, parsed.rejectedLines.size)

        val messy = """
            Asset,OpenTimeMs,Open,High,Low,Close
            EURUSD,1000,1.0,1.2,0.9,1.1
            EURUSD,1000,1.0,1.2,0.9,1.1
            EURUSD,2000,1.0,0.95,0.9,1.1
            EURUSD,abc,1.0,1.2,0.9,1.1
            EURUSD,3000,1.1,1.3,1.0,1.2
        """.trimIndent()
        val r = QuotexCandleCsv.parse(messy)
        assertEquals(2, r.byAsset["EURUSD"]!!.size)
        assertEquals(1, r.duplicates)
        assertEquals(2, r.rejectedLines.size)
    }

    // ---- wording ---------------------------------------------------------------------------------------------------------------

    private fun prediction(decision: QuotexDecision, signal: Signal, reason: String?) = QuotexPrediction(
        lean = QuotexDecision.CALL, decision = decision, probUp = 0.62, confidence = 0.62,
        level = ConfidenceLevel.MEDIUM, signal = signal, agree = 4, voting = 6, totalModels = 6,
        isCandidate = true, waitReason = reason, outputs = emptyList<ModelOutput>(), weights = emptyMap(),
        candleCount = 500, expiryCandles = 4, edge = EdgeAssessment(0, 0, null, null, false, "")
    )

    @Test
    fun wordingNeverPromisesAnOutcome() {
        val banned = listOf("guaranteed", "sure win", "100% accurate", "risk-free", "cannot lose", "sure shot")
        val signalState = QuotexUiState(prediction = prediction(QuotexDecision.CALL, Signal.MEDIUM, null), asset = "EURUSD_OTC", expirySeconds = 60)
        val waitState = QuotexUiState(prediction = prediction(QuotexDecision.WAIT, Signal.WAIT, "WAIT — insufficient signal."))
        val texts = listOf(
            QuotexNarrator.signalText(signalState), QuotexNarrator.spokenSignal(signalState), QuotexNarrator.why(signalState),
            QuotexNarrator.signalText(waitState), QuotexNarrator.spokenSignal(waitState), QuotexNarrator.accuracy(signalState, null),
            QuotexNarrator.HELP
        )
        for (t in texts) for (word in banned) assertFalse("'$word' found in: $t", t.lowercase().contains(word))
        assertTrue(QuotexNarrator.signalText(signalState).contains("CALL"))
        assertTrue(QuotexNarrator.signalText(signalState).contains("62%"))
        assertTrue(QuotexNarrator.signalText(waitState).startsWith("WAIT"))
    }
}
