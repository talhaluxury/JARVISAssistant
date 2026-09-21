package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.quotex.QuotexControls
import com.jarvis.assistant.quotex.QuotexCoordinator

/**
 * Voice/text hook. It only claims an utterance that mentions Quotex, so ordinary JARVIS commands (and the
 * WinGo commands) are never hijacked. Returns null when the utterance is not for Quotex.
 */
class QuotexVoiceController(
    private val coordinatorProvider: () -> QuotexCoordinator,
    private val chatProvider: () -> QuotexChatController,
    private val controls: QuotexControls
) {
    suspend fun tryHandle(rawText: String): String? {
        val t = normalize(rawText)
        if ("quotex" !in t) return null
        if ("start" in t && "monitor" in t) return controls.startMonitoring()
        if ("stop" in t && "monitor" in t) return controls.stopMonitoring()
        coordinatorProvider().ensureReady()
        val chat = chatProvider()
        val question = t.replace("quotex", " ").trim()
        return if ("analy" in t || ("signal" in t && "why" !in t)) chat.spokenSignal() else chat.answer(question)
    }

    private fun normalize(text: String): String {
        var t = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        for (prefix in listOf("hey jarvis ", "jarvis ")) {
            if (t.startsWith(prefix)) t = t.removePrefix(prefix)
        }
        return t
    }
}
