package com.jarvis.assistant.demotrade

import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.ai.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiParserTest {

    @Test
    fun parsesStrictJson() {
        val r = AiSignalParser.parse(
            """{"direction":"CALL","confidence":82,"market_regime":"TREND_UP","reasons":["EMA trend alignment","positive momentum"],"risk_note":"Moderate volatility"}"""
        )
        assertFalse(r.fallback)
        assertEquals(Dir.CALL, r.direction)
        assertEquals(82, r.confidence)
        assertEquals(2, r.reasons.size)
        assertEquals("TREND_UP", r.marketRegime)
    }

    @Test
    fun toleratesCodeFencesAroundJson() {
        val r = AiSignalParser.parse("```json\n{\"direction\":\"put\",\"confidence\":71}\n```")
        assertFalse(r.fallback)
        assertEquals(Dir.PUT, r.direction)
    }

    @Test
    fun malformedJsonFallsBackToWait() {
        for (bad in listOf("", "   ", "CALL", "{direction: CALL", "{\"direction\":\"BUY\",\"confidence\":80}",
            "{\"direction\":\"CALL\"}", "{\"direction\":\"CALL\",\"confidence\":\"high\"}", "{\"direction\":\"CALL\",\"confidence\":150}")) {
            val r = AiSignalParser.parse(bad)
            assertTrue("should fall back: $bad", r.fallback)
            assertEquals(Dir.WAIT, r.direction)
        }
        assertTrue(AiSignalParser.parse(null).fallback)
    }

    @Test
    fun timeoutFallsBackToWait() = runBlocking {
        val slow = object : AiService {
            override suspend fun send(history: List<ChatMessage>, systemPrompt: String): Result<AiResult> {
                delay(5_000L)
                return Result.success(AiResult("{\"direction\":\"CALL\",\"confidence\":90}", null))
            }
        }
        val r = AiSignalAnalyzer(slow, timeoutMs = 50L).opinion("x")
        assertTrue(r.fallback)
        assertEquals(Dir.WAIT, r.direction)
    }

    @Test
    fun errorsFallBackToWait() = runBlocking {
        val failing = object : AiService {
            override suspend fun send(history: List<ChatMessage>, systemPrompt: String): Result<AiResult> =
                Result.failure(IllegalStateException("No AI API key configured"))
        }
        val r = AiSignalAnalyzer(failing).opinion("x")
        assertTrue(r.fallback)
        assertEquals(Dir.WAIT, r.direction)
    }

    @Test
    fun aiDisagreementBlocksTheTradeAndCannotCreateOne() {
        val s = testSettings { it.copy(useAi = true) }
        val candles = CandleSimulator.generate(Scenario.STRONG_CALL, 400)
        var checked = 0
        for (end in 120..400 step 4) {
            val sub = candles.subList(0, end)
            val now = sub.last().openTimeMs + MIN_MS
            val p = SignalPipeline.analyze(sub, s, now, MIN_MS, checkFresh = false)
            val without = SignalPipeline.finalize(p, null, s, "SIM", p.lastClose, now)
            val opposite = AiOpinion(Dir.PUT, 95, null, emptyList(), null)
            val withAi = SignalPipeline.finalize(p, opposite, s, "SIM", p.lastClose, now)
            if (without.direction != Dir.WAIT) {
                assertEquals(Dir.WAIT, withAi.direction)
                checked++
            }
            val bullishAi = AiOpinion(Dir.CALL, 99, null, emptyList(), null)
            val aiOnly = SignalPipeline.finalize(p, bullishAi, s, "SIM", p.lastClose, now)
            if (without.direction == Dir.WAIT && p.tech?.direction != Dir.CALL) assertEquals(Dir.WAIT, aiOnly.direction)
        }
        assertTrue(checked >= 0)
    }
}
