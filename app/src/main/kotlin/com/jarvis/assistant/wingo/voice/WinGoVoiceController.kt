package com.jarvis.assistant.wingo.voice

import com.jarvis.assistant.wingo.WinGoControls
import com.jarvis.assistant.wingo.WinGoCoordinator

/**
 * Voice/text hook. It only claims an utterance when it clearly concerns the WinGo analyzer (mentions
 * the game, or the monitor is already running), so normal JARVIS commands are never hijacked.
 * Returns null when the utterance is not for WinGo.
 */
class WinGoVoiceController(
    private val coordinatorProvider: () -> WinGoCoordinator,
    private val chatProvider: () -> WinGoChatController,
    private val controls: WinGoControls
) {
    suspend fun tryHandle(rawText: String): String? {
        val t = normalize(rawText)
        if (t.isEmpty()) return null
        if ("quotex" in t) return null // Quotex has its own controller
        val gameWord = "game" in t || "wingo" in t || "win go" in t
        // "Jarvis start monitoring" / "stop monitoring": no other JARVIS command uses these phrases.
        if ("start" in t && "monitor" in t) return controls.startMonitoring()
        if ("stop" in t && "monitor" in t) return controls.stopMonitoring()

        val keyword = when {
            "backtest" in t -> "backtest"
            "analyze" in t || "analyse" in t -> "analyze"
            "find the pattern" in t || "what pattern" in t || "show pattern" in t || "matching pattern" in t || "show matching" in t -> "pattern"
            "explain the prediction" in t || "explain prediction" in t || "explain the estimate" in t ||
                "why big" in t || "why small" in t || "why wait" in t -> "why"
            "what is the signal" in t || "show signal" in t || "current signal" in t || "next estimate" in t || "the signal" in t -> "signal"
            "show accuracy" in t || "model performance" in t -> "accuracy"
            "show history" in t || "recent history" in t || "last results" in t -> "history"
            "missing" in t || "history gaps" in t || "history pages" in t -> "gaps"
            else -> return null
        }
        val active = coordinatorProvider().state.value.monitorOn
        val nextRound = "next round" in t
        if (!gameWord && !active && !nextRound) return null

        coordinatorProvider().ensureReady()
        val chat = chatProvider()
        return when (keyword) {
            "signal", "analyze" -> chat.spokenSignal()
            else -> chat.answer(t)
        }
    }

    private fun normalize(text: String): String {
        var t = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        for (prefix in listOf("hey jarvis ", "jarvis ")) {
            if (t.startsWith(prefix)) t = t.removePrefix(prefix)
        }
        return t
    }
}
