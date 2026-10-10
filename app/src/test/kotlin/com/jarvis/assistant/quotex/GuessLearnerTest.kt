package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.agent.GuessLearner
import com.jarvis.assistant.quotex.agent.InMemoryLearnerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuessLearnerTest {
    private val scope = "TEST@60"

    @Test fun startsAtPriorAndFollowsVoteSign() {
        val l = GuessLearner()
        assertTrue(l.predictUp(mapOf("a" to 5.0)) > 0.5)
        assertTrue(l.predictUp(mapOf("a" to -5.0)) < 0.5)
    }

    @Test fun signalThatIsAlwaysWrongLosesInfluence() {
        val l = GuessLearner()
        val before = l.predictUp(mapOf("bad" to 2.0))
        // "bad" votes up every time but the candle is red every time.
        for (i in 0 until 300) l.learn(mapOf("bad" to 2.0), true, false, i * 60_000L, scope)
        assertTrue(l.predictUp(mapOf("bad" to 2.0)) < before)
    }

    @Test fun neverLearnsTheSameCandleTwice() {
        val l = GuessLearner()
        l.learn(mapOf("a" to 1.0), true, true, 1_000L, scope)
        l.learn(mapOf("a" to 1.0), true, true, 1_000L, scope)
        assertEquals(1, l.stats().samples)
    }

    @Test fun savesAndReloads() {
        val store = InMemoryLearnerStore()
        val a = GuessLearner(store)
        for (i in 0 until 50) a.learn(mapOf("x" to 1.0), true, i % 2 == 0, i * 60_000L, scope)
        a.save()
        val b = GuessLearner(store)
        assertEquals(a.stats().samples, b.stats().samples)
        assertEquals(a.predictUp(mapOf("x" to 1.0)), b.predictUp(mapOf("x" to 1.0)), 1e-9)
    }
}
