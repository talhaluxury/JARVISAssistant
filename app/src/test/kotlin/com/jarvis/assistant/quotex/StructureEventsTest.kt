package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.StructureEventType
import com.jarvis.assistant.quotex.analysis.StructureEvents
import com.jarvis.assistant.quotex.domain.Candle
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureEventsTest {
    private fun candles(closes: List<Double>): List<Candle> = closes.mapIndexed { i, c ->
        val prev = if (i == 0) c else closes[i - 1]
        Candle(i * 15_000L, prev, maxOf(prev, c) + 0.0002, minOf(prev, c) - 0.0002, c)
    }

    /** Zig-zag climb (HH/HL) that then closes above its last high: a break of structure up. */
    @Test
    fun uptrendBreakingItsHighIsBreakOfStructure() {
        val base = 1.1000
        val closes = ArrayList<Double>()
        for (wave in 0 until 8) {
            val start = base + wave * 0.0010
            for (k in 0..4) closes.add(start + k * 0.0006)
            for (k in 1..3) closes.add(start + 0.0024 - k * 0.0004)
        }
        for (k in 1..4) closes.add(closes.last() + 0.0008)
        val report = StructureEvents.analyze(PriceSeries(candles(closes)))
        assertTrue(report.events.none { it.type == StructureEventType.CHANGE_OF_CHARACTER_DOWN })
    }

    @Test
    fun tooLittleDataGivesNoEvents() {
        val report = StructureEvents.analyze(PriceSeries(candles(List(10) { 1.1 + it * 0.0001 })))
        assertTrue(report.events.isEmpty() && report.zones.isEmpty())
    }
}
