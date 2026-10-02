package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.quotex.QuotexCoordinator
import com.jarvis.assistant.quotex.agent.AgentNarrator
import com.jarvis.assistant.quotex.agent.toText

/** Answers Quotex chat questions strictly from stored data - nothing is invented. */
class QuotexChatController(private val coordinator: QuotexCoordinator) {

    suspend fun answer(question: String): String {
        val t = question.lowercase().trim()
        coordinator.ensureReady()
        val state = coordinator.state.value
        val number = Regex("\\d+").find(t)?.value?.toIntOrNull()?.coerceIn(1, 2000)
        return when {
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
            else -> QuotexNarrator.HELP
        }
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
