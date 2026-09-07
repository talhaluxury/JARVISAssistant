package com.jarvis.assistant.ai

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.5,
    @SerializedName("max_tokens") val maxTokens: Int = 800
)

data class ChatCompletionResponse(
    val choices: List<Choice>
) {
    data class Choice(val message: ChatMessage)
}

/** Result the rest of the app works with, independent of which provider produced it. */
data class AiResult(
    val replyText: String,
    val commandJson: String?
)
