package com.jarvis.assistant.quotex

import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.quotex.voice.QuotexAiAssistant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotexAiAssistantTest {
    private class Fake(val result: Result<AiResult>) : AiService {
        var lastPrompt = ""
        override suspend fun send(history: List<ChatMessage>, systemPrompt: String): Result<AiResult> {
            lastPrompt = systemPrompt
            return result
        }
    }

    @Test
    fun promptCarriesDataAndHardRules() {
        val fake = Fake(Result.success(AiResult("ok", null)))
        val state = QuotexUiState(asset = "EURUSD_OTC", candleCount = 35, candleSeconds = 10)
        val answer = runBlocking { QuotexAiAssistant(fake).ask("kya hua?", state, null, emptyList()) }
        assertEquals("ok", answer)
        assertTrue(fake.lastPrompt.contains("candles stored: 35"))
        assertTrue(fake.lastPrompt.contains("Never predict the next candle"))
        assertTrue(fake.lastPrompt.contains("Never tell the user to place a trade"))
    }

    @Test
    fun missingKeyGivesReadableMessage() {
        val fake = Fake(Result.failure(IllegalStateException("No AI API key configured. Add one in Settings.")))
        val answer = runBlocking { QuotexAiAssistant(fake).ask("hello", QuotexUiState(), null, emptyList()) }
        assertTrue(answer.contains("AI help is not available"))
    }
}

class QuotexAiTrackerTest {
    private fun c(t: Long, close: Double) = com.jarvis.assistant.quotex.domain.Candle(t, close, close, close, close)

    @Test
    fun callIsScoredFromStoredCandles() {
        val tr = com.jarvis.assistant.quotex.voice.QuotexAiTracker()
        val now = System.currentTimeMillis()
        tr.add(com.jarvis.assistant.quotex.voice.AiCall(now - 60_000, true, 1.1000, now - 30_000))
        tr.resolve(listOf(c(now - 70_000, 1.1001), c(now - 40_000, 1.1004)), 10_000L)
        assertEquals(1, tr.wins)
        assertTrue(tr.summary(0.565).contains("1 right"))
    }

    @Test
    fun noFinishedCandleKeepsCallPending() {
        val tr = com.jarvis.assistant.quotex.voice.QuotexAiTracker()
        val now = System.currentTimeMillis()
        tr.add(com.jarvis.assistant.quotex.voice.AiCall(now, false, 1.1000, now + 60_000))
        tr.resolve(listOf(c(now - 10_000, 1.0990)), 10_000L)
        assertEquals(1, tr.pending)
    }
}
