package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.quotex.QuotexCoordinator

/** Answers Quotex chat questions strictly from stored data - nothing is invented. */
class QuotexChatController(private val coordinator: QuotexCoordinator) {

    suspend fun answer(question: String): String {
        val t = question.lowercase().trim()
        coordinator.ensureReady()
        val state = coordinator.state.value
        val number = Regex("\\d+").find(t)?.value?.toIntOrNull()?.coerceIn(1, 2000)
        return when {
            "risk" in t || "paused" in t || "loss limit" in t || "daily loss" in t -> QuotexNarrator.risk(state)
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

    fun spokenSignal(): String = QuotexNarrator.spokenSignal(coordinator.state.value)
}
