package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.quotex.QuotexAnalytics
import com.jarvis.assistant.quotex.QuotexUiState

/**
 * AI helper for the Quotex chat. It only EXPLAINS what JARVIS measured: it gets a short data brief and strict rules,
 * cannot trigger any command (the reply is shown as text, nothing in it is executed) and may never promise a result.
 */
class QuotexAiAssistant(private val ai: AiService) {

    suspend fun ask(
        question: String, state: QuotexUiState, analytics: QuotexAnalytics?, recent: List<ChatMessage>,
        candles: List<com.jarvis.assistant.quotex.domain.Candle> = emptyList(), analysis: Boolean = false
    ): String {
        val data = brief(state, analytics) + if (analysis) candleTable(candles) else ""
        val result = ai.send(recent + ChatMessage("user", question.take(600)), if (analysis) analysisPrompt(data) else systemPrompt(data))
        return result.fold(
            onSuccess = { it.replyText.ifBlank { "The AI returned an empty answer. Try asking again." } },
            onFailure = { "AI help is not available (${it.message ?: it.javaClass.simpleName}). Add an AI key under Settings to turn it on." }
        )
    }

    companion object {
        /** Facts only, taken from what the app measured. Kept short so every question is cheap. */
        fun brief(state: QuotexUiState, analytics: QuotexAnalytics?): String = buildString {
            appendLine("asset: ${state.asset ?: "unknown"}; live price: ${state.lastPrice ?: "unknown"}")
            appendLine("candle length: ${if (state.candleSeconds > 0) "${state.candleSeconds}s" else "unknown"}; expiry: ${state.expirySeconds}s")
            appendLine("candles stored: ${state.candleCount} (the analysis needs 150)")
            appendLine("screen reader: ${state.screenStatus}; note: ${state.readerNote.take(220)}")
            appendLine("chart candle reading: ${state.chartStatus.take(160)}")
            appendLine("app message: ${state.message?.take(200) ?: "-"}")
            appendLine("break-even win rate needed: ${"%.1f".format(state.breakEven * 100)}%")
            state.risk?.let { appendLine("risk discipline: paused=${it.paused}${if (it.paused) " (${it.reason})" else ""}") }
            state.agent?.let { appendLine("agent read: ${com.jarvis.assistant.quotex.agent.AgentNarrator.current(it).take(500)}") }
            analytics?.let { appendLine("measured session accuracy: ${com.jarvis.assistant.quotex.voice.QuotexNarrator.accuracy(state, it).take(300)}") }
        }

        /** Last candles as compact "o h l c" rows (oldest first) so the AI can look at the real history. */
        fun candleTable(candles: List<com.jarvis.assistant.quotex.domain.Candle>): String {
            if (candles.isEmpty()) return "\nrecent candles: none stored yet"
            return "\nrecent candles (open high low close, oldest first, ${candles.size} rows):\n" +
                candles.takeLast(40).joinToString("\n") { "%.5f %.5f %.5f %.5f".format(it.open, it.high, it.low, it.close) }
        }

        /** Demo-practice opinion: allowed to lean, never to promise. Fixed first line so the app can score it. */
        fun analysisPrompt(data: String): String = """
You are the analysis assistant inside JARVIS Quotex Analyzer. The user practises on a DEMO account. Look at the real candle
history in DATA and give your best honest opinion on the direction over the expiry shown. It is an opinion from the numbers, NOT a
prediction and NOT a promise; you can be wrong and the app keeps score of every opinion.
Answer in EXACTLY this layout (translate the explanations to the user's language, keep the English labels):
LEAN: CALL or PUT or WAIT
CONFIDENCE: LOW or MEDIUM   (never HIGH)
WHY: 2-3 short points that quote numbers from the data (trend of the last candles, highs/lows, momentum, range)
WRONG IF: one line - what price move would make this opinion wrong
Rules: answer WAIT if fewer than 40 candles are stored, if the reader/chart status shows problems, or if the last candles are mixed
and sideways. Do not invent data that is not in DATA. Never mention a stake or tell the user to bet real money. At most about 110 words.

DATA:
$data
""".trimIndent()

        fun systemPrompt(brief: String): String = """
You are the explanation assistant inside JARVIS Quotex Analyzer, an ANALYSIS-ONLY tool. Use ONLY the DATA below.
Rules:
- Never predict the next candle, never say a trade will win, never promise profit, never say a signal is "sure".
- Never tell the user to place a trade or how much to stake. JARVIS cannot trade and you cannot either.
- If data is poor or insufficient (few candles, reader problems), say exactly what is missing and how to fix it in the app (Accessibility service ON, overlay minimised, Top region 0%, chart kept open at one timeframe, asset set manually).
- If the user asks about accuracy or profit: use the measured numbers only; say a win rate below the break-even above loses money; say OTC prices are produced by the broker, so patterns may not carry over; say 100+ measured signals are needed before any number means something.
- Explain terms (trend, structure, confluence, regime) simply when asked.
- If a question is not about the chart or the app, answer in one short line and bring the user back.
- Reply in the same language and script the user writes in (Roman Urdu stays Roman Urdu). At most about 120 words.

DATA:
$brief
""".trimIndent()
    }
}
