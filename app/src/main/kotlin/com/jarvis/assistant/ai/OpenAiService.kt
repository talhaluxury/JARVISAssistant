package com.jarvis.assistant.ai

import com.jarvis.assistant.data.local.prefs.SecurePrefs
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible /v1/chat/completions endpoint. Because the
 * base URL and model are user-configurable in Settings, this same class
 * works with OpenAI directly, or with the user's own proxy/backend if they
 * choose to run one — the API key never ships inside the APK, it is entered
 * once on-device and stored in EncryptedSharedPreferences (see SecurePrefs).
 */
class OpenAiService(private val prefs: SecurePrefs) : AiService {

    override suspend fun send(history: List<ChatMessage>, systemPrompt: String): Result<AiResult> {
        val apiKey = prefs.aiApiKey
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No AI API key configured. Add one in Settings."))
        }
        val baseUrl = prefs.aiBaseUrl?.takeIf { it.isNotBlank() } ?: "https://api.openai.com/"

        return try {
            val api = buildApi(baseUrl)
            val messages = listOf(ChatMessage("system", systemPrompt)) + history
            val response = api.chatCompletion(
                authHeader = "Bearer $apiKey",
                request = ChatCompletionRequest(model = prefs.aiModel, messages = messages)
            )
            val rawText = response.choices.firstOrNull()?.message?.content.orEmpty()
            Result.success(extractCommand(rawText))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildApi(baseUrl: String): OpenAiApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }

    private fun extractCommand(rawText: String): AiResult {
        val regex = Regex("```jarvis_command\\s*([\\s\\S]*?)```")
        val match = regex.find(rawText)
        val commandJson = match?.groupValues?.get(1)?.trim()
        val replyText = if (match != null) rawText.replace(match.value, "").trim() else rawText.trim()
        return AiResult(replyText = replyText, commandJson = commandJson)
    }
}
