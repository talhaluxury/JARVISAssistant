package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.ai.ChatMessage

/** The AI's second opinion on one local guess. [call] is UP, DOWN or SKIP. */
data class AiVerdict(val call: String, val confidence: Int, val reason: String, val forOpenMs: Long)

/**
 * Asks the configured AI for a second opinion on a STRONG local guess. The AI can only agree, disagree or say SKIP:
 * it never starts a trade by itself, so in the auto switch it can only VETO a tap, never cause one.
 */
class QuickGuessAdvisor(private val ai: AiService) {

    suspend fun verdict(guess: QuickGuess): AiVerdict? {
        val result = ai.send(listOf(ChatMessage("user", guess.brief)), SYSTEM_PROMPT)
        val text = result.getOrNull()?.replyText ?: return null
        return parse(text, guess.forOpenMs)
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a cautious chart-reading checker inside a DEMO practice tool. You get a compact summary of the latest " +
                "candles and a local engine's call for the NEXT candle. Decide whether the data really supports that call. " +
                "Short-term candles are close to random, so answer SKIP whenever the evidence is mixed, choppy or thin; " +
                "never claim certainty. Reply with ONLY one line of JSON and nothing else: " +
                "{\"call\":\"UP\"|\"DOWN\"|\"SKIP\",\"confidence\":0-100,\"reason\":\"max 12 words\"}"

        fun parse(text: String, forOpenMs: Long): AiVerdict? {
            val call = Regex("\"call\"\\s*:\\s*\"(UP|DOWN|SKIP)\"", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase() ?: return null
            val conf = Regex("\"confidence\"\\s*:\\s*(\\d{1,3})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
            val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]{0,120})\"").find(text)?.groupValues?.get(1).orEmpty()
            return AiVerdict(call, conf, reason, forOpenMs)
        }
    }
}
