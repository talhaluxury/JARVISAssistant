package com.jarvis.assistant.wingo.voice

import com.jarvis.assistant.wingo.WinGoCoordinator
import com.jarvis.assistant.wingo.domain.BigSmall

/** Answers WinGo chat questions strictly from stored data - nothing is invented. */
class WinGoChatController(private val coordinator: WinGoCoordinator) {

    suspend fun answer(question: String): String {
        val t = question.lowercase().trim()
        val number = Regex("\\d+").find(t)?.value?.toIntOrNull()?.coerceIn(1, 500)
        return when {
            "backtest" in t && "pattern" in t -> WinGoNarrator.patternBacktest(coordinator.backtestCurrentPattern())
            "backtest" in t -> {
                coordinator.ensureReady()
                coordinator.runBacktest(number).toText()
            }
            "missing" in t || "gap" in t || "coverage" in t || "history page" in t -> {
                coordinator.ensureReady()
                WinGoNarrator.coverage(coordinator.state.value)
            }
            "matching" in t -> {
                coordinator.ensureReady()
                WinGoNarrator.matchingPatterns(coordinator.state.value)
            }
            "pattern" in t -> {
                coordinator.ensureReady()
                WinGoNarrator.pattern(coordinator.state.value)
            }
            "why" in t || "explain" in t -> {
                coordinator.ensureReady()
                val requested = when {
                    "big" in t -> BigSmall.BIG
                    "small" in t -> BigSmall.SMALL
                    else -> null
                }
                WinGoNarrator.why(coordinator.state.value, requested)
            }
            "model performance" in t || "models" in t -> {
                coordinator.ensureReady()
                WinGoNarrator.modelPerformance(coordinator.state.value)
            }
            "accuracy" in t || "performance" in t || "win rate" in t || "how accurate" in t -> {
                coordinator.ensureReady()
                WinGoNarrator.accuracy(coordinator.state.value, coordinator.analytics())
            }
            "verify" in t || "verified" in t -> WinGoNarrator.verification(coordinator.state.value)
            (("last" in t || "recent" in t) && ("round" in t || "result" in t)) || "history" in t ->
                WinGoNarrator.history(coordinator.recentResults(number ?: 10))
            "signal" in t || "analyze" in t || "analyse" in t || "predict" in t || "next" in t || "estimate" in t -> {
                coordinator.ensureReady()
                WinGoNarrator.signalText(coordinator.state.value)
            }
            else -> WinGoNarrator.HELP
        }
    }

    fun spokenSignal(): String = WinGoNarrator.spokenSignal(coordinator.state.value)
}
