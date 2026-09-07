package com.jarvis.assistant.search

import com.jarvis.assistant.data.local.prefs.SecurePrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** True if the message needs live/current info rather than the model's own knowledge. */
fun needsWebSearch(text: String): Boolean {
    val t = text.lowercase()
    val triggers = listOf(
        "today", "current", "latest", "news", "weather", "price of", "score",
        "who is the current", "right now", "aaj", "abhi", "mausam"
    )
    return triggers.any { t.contains(it) }
}

/**
 * Uses Brave Search's API (the user supplies their own key in Settings) to
 * pull a few current results, which are handed to the AI as context. If no
 * search key is configured, this fails gracefully and the assistant says so
 * instead of guessing.
 */
class WebSearchService(private val prefs: SecurePrefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): Result<String> {
        val key = prefs.searchApiKey
        if (key.isNullOrBlank()) {
            return Result.failure(IllegalStateException("No search API key configured. Add one in Settings to enable live search."))
        }
        return try {
            val url = "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=5"
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("X-Subscription-Token", key)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Search failed (${response.code})."))
                }
                val body = response.body?.string().orEmpty()
                Result.success(summarizeResults(body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun summarizeResults(json: String): String {
        return try {
            val root = org.json.JSONObject(json)
            val results: JSONArray = root.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
            buildString {
                for (i in 0 until minOf(5, results.length())) {
                    val item = results.getJSONObject(i)
                    val title = item.optString("title")
                    val description = item.optString("description")
                    append("- $title: $description\n")
                }
            }.trim()
        } catch (e: Exception) {
            ""
        }
    }
}
