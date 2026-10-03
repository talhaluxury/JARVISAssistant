package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.ocr.CountdownTimeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountdownTimeframeTest {
    @Test
    fun tenSecondChartIsRecognisedAfterOneCandle() {
        var now = 1_000_000L
        val c = CountdownTimeframe { now }
        assertNull(c.add(7))
        now += 5_000; assertNull(c.add(2))
        now += 3_000; assertNull(c.add(10)) // new candle restarted at 10 - but a full candle has not been observed yet
        now += 5_000
        assertEquals(10, c.add(5))
    }

    @Test
    fun sixtySecondChartNeedsAFullCandle() {
        var now = 0L
        val c = CountdownTimeframe { now }
        assertNull(c.add(40))
        now += 30_000; assertNull(c.add(10))
        now += 45_000
        assertEquals(60, c.add(58))
    }
}
