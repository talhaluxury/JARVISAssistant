package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.ocr.ChartRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChartRefinerTest {
    private val sampled = Candle(1000L, 1.08200, 1.08210, 1.08195, 1.08205)

    @Test
    fun chartWicksReplaceSampledOnesWhenBodyAgrees() {
        val chart = Candle(1000L, 1.08201, 1.08230, 1.08180, 1.08205)
        assertSame(chart, ChartRefiner.refine(sampled, chart))
    }

    @Test
    fun differentBarIsIgnored() {
        assertSame(sampled, ChartRefiner.refine(sampled, Candle(2000L, 1.08200, 1.08230, 1.08180, 1.08205)))
    }

    @Test
    fun disagreeingBodyIsIgnored() {
        assertSame(sampled, ChartRefiner.refine(sampled, Candle(1000L, 1.0900, 1.0910, 1.0890, 1.0905)))
    }

    @Test
    fun absurdWickIsIgnored() {
        assertSame(sampled, ChartRefiner.refine(sampled, Candle(1000L, 1.08200, 1.2, 1.08195, 1.08205)))
    }

    @Test
    fun missingChartCandleKeepsSampled() {
        assertEquals(sampled, ChartRefiner.refine(sampled, null))
    }
}
