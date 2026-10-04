package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.quotex.QuotexCoordinator
import com.jarvis.assistant.quotex.agent.AgentNarrator
import com.jarvis.assistant.quotex.agent.toText
import com.jarvis.assistant.quotex.pro.ProNarrator

/** Answers Quotex chat questions strictly from stored data - nothing is invented. */
class QuotexChatController(private val coordinator: QuotexCoordinator, private val ai: QuotexAiAssistant? = null) {
    private val recent = ArrayList<com.jarvis.assistant.ai.ChatMessage>()
    private val tracker = QuotexAiTracker()

    suspend fun answer(question: String): String {
        val t = question.lowercase().trim()
        coordinator.ensureReady()
        val state = coordinator.state.value
        // "ai ..." always goes to the AI helper; everything the fixed rules below do not understand goes there too.
        val aiPrefix = t.startsWith("ai ") || t.startsWith("ai:") || t.startsWith("/ai")
        if (aiPrefix && ai != null) {
            val asked = question.trim().removePrefix("/ai").removePrefix("ai:").removePrefix("AI:").removePrefix("ai ").removePrefix("AI ").trim()
            val low = asked.lowercase()
            return when {
                listOf("score", "record", "result", "win", "natija", "hisab").any { it in low } -> scoreText(state)
                listOf("analy", "signal", "sahi", "galat", "check", "entry", "trade", "call", "put", "kya karu", "direction").any { it in low } -> analyse(asked, state)
                else -> askAi(asked, state)
            }
        }
        val number = Regex("\\d+").find(t)?.value?.toIntOrNull()?.coerceIn(1, 2000)
        return when {
            "pause" in t && "analysis" in t -> { coordinator.setRiskPaused(true); "Analysis paused. Say 'resume analysis' to continue. Nothing is traded either way." }
            "resume" in t && "analysis" in t -> { coordinator.setRiskPaused(false); "Analysis resumed." }
            "analyze chart" in t || "analyse chart" in t || "show current signal" in t || "current signal" in t -> ProNarrator.signal(state.agent)
            "show reasons" in t || "reasons" in t && "show" in t -> ProNarrator.reasons(state.agent)
            "strategy lab" in t || "my strategies" in t -> coordinator.labSummary()
            "news" in t -> ProNarrator.news(state.agent)
            "market structure" in t -> ProNarrator.structure(state.agent)
            "historical performance" in t || "show history" in t -> ProNarrator.performance(coordinator.journalLast(500))
            "monte carlo" in t || "robustness" in t -> ProNarrator.monteCarlo(coordinator.journalLast(500))
            "risk" in t || "paused" in t || "loss limit" in t || "daily loss" in t -> QuotexNarrator.risk(state)
            "signal journal" in t || "signal history" in t ->
                QuotexNarrator.journalSummary(coordinator.journalRecent(number ?: 10), "Signal journal")
            "journal" in t || ("last" in t && "setups" in t) -> AgentNarrator.journalList(coordinator.journalLast(number ?: 20))
            "fail" in t -> AgentNarrator.whyFailed(coordinator.journal())
            "today" in t && ("performance" in t || "result" in t) ->
                AgentNarrator.summary("Today", coordinator.journal().summary(coordinator.journalSince(startOfToday())))
            ("walk" in t || "out of sample" in t || "agent" in t) && "backtest" in t -> coordinator.runAgentBacktest(number).toText()
            "no trade" in t || "should i wait" in t || t == "wait" || "why wait" in t -> AgentNarrator.whyNoTrade(state.agent)
            "confluence" in t && state.agent != null && "backtest" !in t -> AgentNarrator.confluence(state.agent)
            ("why" in t || "explain" in t) && state.agent != null -> AgentNarrator.explain(state.agent)
            ("current setup" in t || "what is the" in t && "setup" in t) && state.agent != null -> AgentNarrator.current(state.agent)
            ("confluence" in t || "strategy" in t) && "backtest" in t -> coordinator.runConfluenceBacktest(number).toText()
            "backtest" in t -> coordinator.runBacktest(number).toText()
            "strategy" in t && ("performance" in t || "accuracy" in t) -> QuotexNarrator.strategyPerformance(coordinator.strategyStatuses())
            "confluence" in t || "setup" in t || "strategy" in t || "strategies" in t -> QuotexNarrator.confluence(state)
            "why" in t || "explain" in t -> QuotexNarrator.why(state)
            "accuracy" in t || "performance" in t || "win rate" in t -> QuotexNarrator.accuracy(state, coordinator.analytics())
            "price" in t || "asset" in t || "candle" in t -> QuotexNarrator.price(state)
            "signal" in t || "analyze" in t || "analyse" in t || "predict" in t || "trade" in t || "call" in t || "put" in t ->
                QuotexNarrator.signalText(state)
            else -> if (ai != null) askAi(question, state) else QuotexNarrator.HELP
        }
    }

    /** Demo-practice opinion from the real stored candles; the call is logged and scored later by the chart's own candles. */
    private suspend fun analyse(question: String, state: com.jarvis.assistant.quotex.QuotexUiState): String {
        val candles = coordinator.candlesTail(40)
        val answer = ai!!.ask(question, state, runCatching { coordinator.analytics() }.getOrNull(), emptyList(), candles, analysis = true)
        val lean = Regex("LEAN:\\s*(CALL|PUT)", RegexOption.IGNORE_CASE).find(answer)?.groupValues?.get(1)?.uppercase()
        val entry = state.lastPrice
        if (lean != null && entry != null) {
            val now = System.currentTimeMillis()
            tracker.add(AiCall(now, lean == "CALL", entry, now + state.expirySeconds * 1000L))
        }
        return answer + "\n\n" + "(Demo practice. Opinion only; JARVIS will score it after ${state.expirySeconds}s. Ask 'ai score'.)"
    }

    private suspend fun scoreText(state: com.jarvis.assistant.quotex.QuotexUiState): String {
        val candleMs = (if (state.candleSeconds > 0) state.candleSeconds else 10) * 1000L
        tracker.resolve(coordinator.candlesTail(400), candleMs)
        return tracker.summary(state.breakEven)
    }

    private suspend fun askAi(question: String, state: com.jarvis.assistant.quotex.QuotexUiState): String {
        val answer = ai!!.ask(question, state, runCatching { coordinator.analytics() }.getOrNull(), recent.takeLast(6))
        recent.add(com.jarvis.assistant.ai.ChatMessage("user", question.take(600)))
        recent.add(com.jarvis.assistant.ai.ChatMessage("assistant", answer.take(600)))
        while (recent.size > 12) recent.removeAt(0)
        return answer
    }

    fun spokenSignal(): String {
        val agent = coordinator.state.value.agent
        return if (agent != null) AgentNarrator.speech(agent) else QuotexNarrator.spokenSignal(coordinator.state.value)
    }

    private fun startOfToday(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
