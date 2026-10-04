package com.jarvis.assistant.demotrade

import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.ai.ChatMessage
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The AI's opinion. It is only ever ONE input to the consensus; it cannot open a trade on its own.
 * [fallback] = true means the AI did not give a usable answer (timeout, error, malformed JSON) and the opinion is a safe WAIT.
 */
data class AiOpinion(
    val direction: Dir,
    val confidence: Int,
    val marketRegime: String?,
    val reasons: List<String>,
    val riskNote: String?,
    val fallback: Boolean = false,
    val fallbackReason: String? = null
) {
    companion object {
        fun fallback(reason: String) = AiOpinion(Dir.WAIT, 0, null, emptyList(), null, true, reason)
    }
}

/** Strict parser: anything that is not exactly the documented JSON becomes a safe WAIT. Never throws. */
object AiSignalParser {
    fun parse(raw: String?): AiOpinion {
        if (raw.isNullOrBlank()) return AiOpinion.fallback("empty AI response")
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return AiOpinion.fallback("no JSON object in AI response")
        return try {
            val obj: JsonObject = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            val dirText = (obj["direction"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
            val dir = when (dirText) {
                "CALL" -> Dir.CALL
                "PUT" -> Dir.PUT
                "WAIT" -> Dir.WAIT
                else -> return AiOpinion.fallback("invalid direction '$dirText'")
            }
            val confValue = (obj["confidence"] as? JsonPrimitive)?.doubleOrNull
                ?: return AiOpinion.fallback("missing or non-numeric confidence")
            if (confValue.isNaN() || confValue < 0.0 || confValue > 100.0) return AiOpinion.fallback("confidence out of range")
            val reasons = (obj["reasons"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.map { it.take(120) }
                ?.take(6)
                ?: emptyList()
            val regime = (obj["market_regime"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val note = (obj["risk_note"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.take(160)
            AiOpinion(dir, confValue.toInt(), regime, reasons, note)
        } catch (e: Exception) {
            AiOpinion.fallback("malformed JSON")
        }
    }
}

/** Anything that can answer "what do you think of these measured features?" - the real one wraps the app's AiService. */
interface AiOpinionProvider {
    suspend fun opinion(prompt: String): AiOpinion
}

class AiSignalAnalyzer(
    private val ai: AiService,
    private val timeoutMs: Long = 20_000L
) : AiOpinionProvider {
    override suspend fun opinion(prompt: String): AiOpinion {
        val result: Result<com.jarvis.assistant.ai.AiResult>? = try {
            withTimeoutOrNull(timeoutMs) { ai.send(listOf(ChatMessage("user", prompt)), SYSTEM_PROMPT) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AiOpinion.fallback("AI error: ${e.message ?: e.javaClass.simpleName}")
        }
        if (result == null) return AiOpinion.fallback("AI timed out")
        return result.fold(
            onSuccess = { AiSignalParser.parse(it.replyText) },
            onFailure = { AiOpinion.fallback("AI unavailable: ${it.message ?: it.javaClass.simpleName}") }
        )
    }

    companion object {
        val SYSTEM_PROMPT = """
You are the analysis layer of a DEMO / PAPER trading simulator. You receive indicator values that were MEASURED by a
deterministic engine. Give an honest opinion on the likely direction at the stated expiry. You are NOT a predictor and you
can be wrong; the app scores every opinion. Prefer WAIT whenever the data is mixed, the market is ranging or volatile, or
anything is missing. Never invent data that is not in the message.
Reply with ONLY one JSON object, no markdown, no extra text, exactly this shape:
{"direction":"CALL|PUT|WAIT","confidence":0-100,"market_regime":"TREND_UP|TREND_DOWN|RANGE|HIGH_VOLATILITY|LOW_VOLATILITY|BREAKOUT|UNCERTAIN","reasons":["short reason","short reason"],"risk_note":"short note"}
        """.trimIndent()

        fun buildPrompt(p: PipelineResult, asset: String, settings: DemoSettings): String = buildString {
            appendLine("asset: ${asset.ifBlank { "unknown" }}; expiry: ${settings.expirySeconds}s")
            appendLine("engine regime: ${p.regime?.regime ?: "n/a"}")
            p.mtf?.let { appendLine(it.summary) }
            p.tech?.let { appendLine("technical score: ${it.direction} ${it.score}/100") }
            p.ensemble?.let { appendLine("strategy ensemble: ${it.direction} ${it.score}/100, confirmations ${it.confirmationText}") }
            appendLine("strategy votes:")
            for (v in p.votes) appendLine("- ${v.strategy}: ${v.direction} ${v.score} (${v.reasons.joinToString("; ")})")
            p.patterns.takeIf { it.isNotEmpty() }?.let { appendLine("candlestick patterns: ${it.joinToString { x -> x.name }}") }
            appendLine("indicators at the last closed candle:")
            p.ind?.snapshot()?.forEach { (k, v) -> appendLine("$k=${String.format(java.util.Locale.US, "%.6f", v)}") }
        }
    }
}
