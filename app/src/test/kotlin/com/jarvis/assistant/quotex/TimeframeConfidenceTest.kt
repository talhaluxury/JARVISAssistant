package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.agent.MultiTimeframeConfluenceStrategy
import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.StructureLabel
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.domain.QuotexConfig
import com.jarvis.assistant.quotex.domain.Timeframe
import com.jarvis.assistant.quotex.domain.TimeframePlan
import com.jarvis.assistant.quotex.ocr.QuotexScreenParser
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeframeConfidenceTest {

    // ---- timeframes ------------------------------------------------------------------------------------------

    @Test
    fun supportedTimeframesAreOneFiveFifteenThirtySixty() {
        assertEquals(listOf(60, 300, 900, 1800, 3600), Timeframe.supportedSeconds)
    }

    @Test
    fun planFactorsAreExactMultiplesOfTheEntryTimeframe() {
        val m1 = TimeframePlan.forEntry(60)
        assertEquals(5, m1.middleFactor); assertEquals(15, m1.higherFactor)
        val m5 = TimeframePlan.forEntry(300)
        assertEquals(3, m5.middleFactor); assertEquals(12, m5.higherFactor)
        val m15 = TimeframePlan.forEntry(900)
        assertEquals(2, m15.middleFactor); assertEquals(4, m15.higherFactor)
        val m30 = TimeframePlan.forEntry(1800)
        assertEquals(2, m30.middleFactor); assertNull(m30.higherFactor)
        val h1 = TimeframePlan.forEntry(3600)
        assertNull(h1.middleFactor); assertNull(h1.higherFactor)
    }

    @Test
    fun legacySubMinuteCandlesKeepTheOldFourAndTwelve() {
        val p = TimeframePlan.forEntry(15)
        assertEquals(4, p.middleFactor); assertEquals(12, p.higherFactor)
    }

    @Test
    fun configExposesThePlanForItsCandleLength() {
        assertEquals(12, QuotexConfig(candleSeconds = 300).timeframePlan.higherFactor)
    }

    @Test
    fun mtfStrategyAbstainsWhenNoHigherLevelExists() {
        val candles = (0 until 400).map { i -> val p = 100.0 + i * 0.1; Candle(i * 3_600_000L, p, p + 0.2, p - 0.1, p + 0.15) }
        val s = PriceSeries(candles)
        val r = MultiTimeframeConfluenceStrategy(null, null).evaluate(
            s, MarketStructure.trendLabel(s), StructureLabel.UNCLEAR, MarketStructure.volatilityLabel(s), MarketStructure.swings(s)
        )
        assertEquals(QuotexDecision.WAIT, r.direction)
    }

    // ---- OCR confidence ----------------------------------------------------------------------------------------

    private fun word(text: String, left: Int, centerY: Int, conf: Float) = OcrLine(text, left, centerY - 15, left + 100, centerY + 15, conf)

    private fun axis(conf: Float, liveConf: Float = conf): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        listOf("1.08200", "1.08220", "1.08240", "1.08260", "1.08280").forEachIndexed { i, t -> lines.add(word(t, 900, 500 - i * 100, conf)) }
        lines.add(word("1.08253", 900, 235, liveConf))
        lines.add(word("EUR/USD", 20, 45, 0.9f))
        return lines
    }

    @Test
    fun readingCarriesTheMeanAxisConfidence() {
        val reading = QuotexScreenParser().parse(axis(0.9f), 1080, 1600)
        assertNotNull(reading.price)
        assertEquals(0.9, reading.confidence!!, 1e-6)
    }

    @Test
    fun aShakyLivePriceLabelCapsTheConfidence() {
        val reading = QuotexScreenParser().parse(axis(0.95f, liveConf = 0.40f), 1080, 1600)
        assertEquals(0.40, reading.confidence!!, 1e-6)
    }

    @Test
    fun confidenceIsNullWhenTheEngineReportsNone() {
        val reading = QuotexScreenParser().parse(axis(0f), 1080, 1600)
        assertNotNull(reading.price)
        assertNull(reading.confidence)
    }

    @Test
    fun gridLabelsExcludeTheLivePriceChip() {
        val reading = QuotexScreenParser().parse(axis(0.9f), 1080, 1600)
        assertEquals(5, reading.gridLabels.size)
        assertTrue(reading.gridLabels.none { it.value == 1.08253 })
    }
}
