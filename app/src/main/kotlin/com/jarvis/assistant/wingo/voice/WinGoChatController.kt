package com.jarvis.assistant.wingo.voice

import com.jarvis.assistant.wingo.WinGoCoordinator
import com.jarvis.assistant.wingo.domain.Signal

/** Answers WinGo chat questions strictly from stored data - nothing is invented. */
class WinGoChatController(private val coordinator: WinGoCoordinator) {

    suspend fun answer(question: String): String {
        val t = question.lowercase().trim()
        val state = coordinator.state.value
        val number = Regex("\\d+").find(t)?.value?.toIntOrNull()?.coerceIn(1, 500)
        return when {
            "backtest" in t -> {
                coordinator.ensureReady()
                coordinator.runBacktest(number).toText()
            }
            "why" in t && ("wait" in t || state.prediction?.signal == Signal.WAIT) ->
                WinGoNarrator.why(state)
            "why" in t || "explain" in t -> WinGoNarrator.why(state)
            "accuracy" in t || "performance" in t || "win rate" in t || "how accurate" in t -> {
                val snapshot = coordinator.analytics()
                WinGoNarrator.accuracy(state, snapshot)
            }
            ("last" in t && "round" in t) || "history" in t || "recent" in t ->
                WinGoNarrator.history(coordinator.recentResults(number ?: 10))
            "signal" in t || "analyze" in t || "analyse" in t || "predict" in t || "next" in t ->
                WinGoNarrator.signalText(state)
            else -> WinGoNarrator.HELP
        }
    }

    fun spokenSignal(): String = WinGoNarrator.spokenSignal(coordinator.state.value)
}
