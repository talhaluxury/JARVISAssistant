package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataValidatorTest {
    private val now = T0 + 100 * MIN_MS

    private fun validate(c: List<Candle>, min: Int = 20) = DataValidator.validate(c, MIN_MS, now, min)

    @Test
    fun acceptsCleanRecentData() {
        val c = flatCandles(60, start = now - 60 * MIN_MS)
        assertTrue(validate(c, 55).ok)
    }

    @Test
    fun emptyIsUnavailable() {
        val r = validate(emptyList())
        assertFalse(r.ok)
        assertTrue(r.unavailable)
    }

    @Test
    fun tooFewCandlesIsNotAnOutageJustWaiting() {
        val r = validate(flatCandles(10, start = now - 10 * MIN_MS), 55)
        assertFalse(r.ok)
        assertFalse(r.unavailable)
    }

    @Test
    fun rejectsDuplicateTimestamps() {
        val c = flatCandles(30, start = now - 30 * MIN_MS).toMutableList()
        c[20] = c[19]
        val r = validate(c)
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("duplicate"))
    }

    @Test
    fun rejectsFutureTimestamps() {
        val c = flatCandles(30, start = now - 29 * MIN_MS + 5 * MIN_MS)
        val r = validate(c)
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("future"))
    }

    @Test
    fun rejectsMissingCandles() {
        val a = flatCandles(25, start = now - 40 * MIN_MS)
        val b = flatCandles(5, start = now - 10 * MIN_MS)
        val r = validate(a + b)
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("missing"))
    }

    @Test
    fun rejectsLargeJumpsAndImpossiblePrices() {
        val c = flatCandles(30, start = now - 30 * MIN_MS).toMutableList()
        c[25] = Candle(c[25].openTimeMs, 1.5, 1.5001, 1.4999, 1.5)
        assertFalse(validate(c).ok)
        val bad = flatCandles(30, start = now - 30 * MIN_MS).toMutableList()
        bad[10] = Candle(bad[10].openTimeMs, 0.0, 0.0001, -0.0001, 0.0)
        assertFalse(validate(bad).ok)
    }

    @Test
    fun rejectsNaNPrices() {
        val c = flatCandles(30, start = now - 30 * MIN_MS).toMutableList()
        c[29] = Candle(c[29].openTimeMs, Double.NaN, 1.1, 1.1, 1.1)
        assertFalse(validate(c).ok)
    }

    @Test
    fun rejectsStaleData() {
        val c = flatCandles(30, start = now - 60 * MIN_MS)
        val r = validate(c)
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("stale"))
    }

    @Test
    fun unknownCandleLengthIsUnavailable() {
        val r = DataValidator.validate(flatCandles(30, start = now - 30 * MIN_MS), 0L, now, 20)
        assertEquals(true, r.unavailable)
    }
}
