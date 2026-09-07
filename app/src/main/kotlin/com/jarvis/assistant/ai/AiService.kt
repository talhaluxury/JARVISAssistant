package com.jarvis.assistant.ai

/**
 * Abstraction over the AI provider so the rest of the app never depends on
 * a specific vendor's API. Swap the implementation in AppContainer to
 * change providers without touching UI/ViewModel/Command code.
 */
interface AiService {
    suspend fun send(history: List<ChatMessage>, systemPrompt: String): Result<AiResult>
}
