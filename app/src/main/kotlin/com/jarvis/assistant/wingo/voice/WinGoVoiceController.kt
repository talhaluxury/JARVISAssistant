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
        val gameWord = "game" in t || "wingo" in t || "win go" in t
        val startsMonitor = "start" in t && "monitor" in t && gameWord
        val stopsMonitor = "stop" in t && "monitor" in t && gameWord
        if (startsMonitor) return controls.startMonitoring()
        if (stopsMonitor) return controls.stopMonitoring()

        val keyword = when {
            "backtest" in t -> "backtest"
            "analyze" in t || "analyse" in t -> "analyze"
            "show signal" in t || "current signal" in t -> "signal"
            "show accuracy" in t -> "accuracy"
            "show history" in t || "recent history" in t -> "history"
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
