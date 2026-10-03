package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.ocr.HistoryStitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStitcherTest {
    private val ms = 60_000L

    /** A varied price path so no two windows look alike. */
    private fun world(n: Int): List<Candle> = (0 until n).map { i ->
        val base = 1.1000 + 0.0003 * ((i * 7) % 11) + 0.00001 * i
        Candle(i * ms, base, base + 0.0002, base - 0.0002, base + 0.0001 * (if (i % 2 == 0) 1 else -1))
    }

    private fun window(all: List<Candle>, from: Int, size: Int) = all.subList(from, from + size).map { Candle(0L, it.open, it.high, it.low, it.close) }

    @Test
    fun scrollingBackExtendsHistoryWithCorrectTimes() {
        val all = world(100)
        val st = HistoryStitcher(ms)
        st.seed(all.subList(70, 100)) // live frame shows candles 70..99 with their real times
        assertEquals(HistoryStitcher.Result.EXTENDED, st.add(window(all, 50, 30))) // 50..79 overlaps 70..79
        assertEquals(50, st.size)
        assertEquals(HistoryStitcher.Result.EXTENDED, st.add(window(all, 30, 30)))
        assertEquals(70, st.size)
        val got = st.candles
        for (i in got.indices) {
            assertEquals(all[30 + i].openTimeMs, got[i].openTimeMs)
            assertEquals(all[30 + i].close, got[i].close, 1e-9)
        }
    }

    @Test
    fun aFrameWithoutOverlapIsRejected() {
        val all = world(100)
        val st = HistoryStitcher(ms)
        st.seed(all.subList(70, 100))
        assertEquals(HistoryStitcher.Result.NO_OVERLAP, st.add(window(all, 10, 30))) // 10..39: nothing shared
        assertEquals(30, st.size)
    }

    @Test
    fun aFrameInsideKnownHistoryAddsNothing() {
        val all = world(100)
        val st = HistoryStitcher(ms)
        st.seed(all.subList(60, 100))
        assertEquals(HistoryStitcher.Result.NO_NEW_CANDLES, st.add(window(all, 65, 20)))
        assertTrue(st.size == 40)
    }
}
