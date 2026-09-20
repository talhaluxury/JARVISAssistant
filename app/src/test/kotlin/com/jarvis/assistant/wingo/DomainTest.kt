package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.PeriodFormat
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.WinGoColors
import com.jarvis.assistant.wingo.domain.WinGoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DomainTest {

    @Test
    fun numbersZeroToFourAreSmallAndFiveToNineAreBig() {
        for (n in 0..4) assertEquals(BigSmall.SMALL, BigSmall.fromNumber(n))
        for (n in 5..9) assertEquals(BigSmall.BIG, BigSmall.fromNumber(n))
    }

    @Test
    fun numbersOutsideZeroToNineAreRejected() {
        try {
            BigSmall.fromNumber(10)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            RoundResult("20260920100051299", -1, 0L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun parsesBigSmallLabels() {
        assertEquals(BigSmall.BIG, BigSmall.parse(" big "))
        assertEquals(BigSmall.SMALL, BigSmall.parse("SMALL"))
        assertNull(BigSmall.parse("medium"))
        assertNull(BigSmall.parse(null))
    }

    @Test
    fun colourRuleIsDeterministic() {
        assertEquals("RED+VIOLET", WinGoColors.forNumber(0))
        assertEquals("GREEN+VIOLET", WinGoColors.forNumber(5))
        for (n in listOf(1, 3, 7, 9)) assertEquals("GREEN", WinGoColors.forNumber(n))
        for (n in listOf(2, 4, 6, 8)) assertEquals("RED", WinGoColors.forNumber(n))
        assertEquals(setOf("RED", "VIOLET"), WinGoColors.parts("Red + Violet"))
        assertEquals(setOf("GREEN", "VIOLET"), WinGoColors.parts("green/purple"))
    }

    @Test
    fun periodFormatValidation() {
        assertTrue(PeriodFormat.isValid("20260920100051299"))
        assertFalse(PeriodFormat.isValid("2026092010005129")) // too short
        assertFalse(PeriodFormat.isValid("202609201000512999")) // too long
        assertFalse(PeriodFormat.isValid("2026092010005129x")) // not numeric
        assertFalse(PeriodFormat.isValid("20261320100051299")) // month 13
        assertFalse(PeriodFormat.isValid("20260931100051299")) // 31 September
        assertEquals("20260920100051300", PeriodFormat.next("20260920100051299"))
        assertNull(PeriodFormat.next("abc"))
    }

    @Test
    fun confidenceLevelsFollowConfiguredThresholds() {
        val c = WinGoConfig()
        assertEquals(ConfidenceLevel.VERY_LOW, c.levelFor(0.54))
        assertEquals(ConfidenceLevel.LOW, c.levelFor(0.55))
        assertEquals(ConfidenceLevel.LOW, c.levelFor(0.59))
        assertEquals(ConfidenceLevel.MEDIUM, c.levelFor(0.60))
        assertEquals(ConfidenceLevel.MEDIUM, c.levelFor(0.69))
        assertEquals(ConfidenceLevel.HIGH, c.levelFor(0.70))
        val custom = WinGoConfig(lowThreshold = 0.52, mediumThreshold = 0.58, highThreshold = 0.65)
        assertEquals(ConfidenceLevel.LOW, custom.levelFor(0.53))
        assertEquals(ConfidenceLevel.HIGH, custom.levelFor(0.66))
    }

    @Test
    fun invalidThresholdOrderIsRejected() {
        try {
            WinGoConfig(lowThreshold = 0.60, mediumThreshold = 0.55, highThreshold = 0.70)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
