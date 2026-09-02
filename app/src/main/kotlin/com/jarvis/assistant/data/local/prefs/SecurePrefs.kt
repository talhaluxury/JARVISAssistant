package com.jarvis.assistant.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's own AI provider API key and app settings using
 * Android Keystore-backed AES encryption. Nothing here is ever bundled
 * in the APK — the key is typed in once on the Settings screen and
 * lives only in this encrypted file on the device.
 */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var aiApiKey: String?
        get() = prefs.getString(KEY_AI_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_AI_API_KEY, value).apply()

    var aiBaseUrl: String?
        get() = prefs.getString(KEY_AI_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_AI_BASE_URL, value).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value).apply()

    var searchApiKey: String?
        get() = prefs.getString(KEY_SEARCH_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_SEARCH_API_KEY, value).apply()

    var preferredLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value).apply()

    var voiceName: String?
        get() = prefs.getString(KEY_VOICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_VOICE_NAME, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD, value).apply()

    /** When on, every action (even ones that don't normally need it) asks for a yes/no first. */
    var confirmEveryAction: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_EVERY_ACTION, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_EVERY_ACTION, value).apply()

    /** Whether the floating background mic bubble should be running. */
    var backgroundJarvisEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_JARVIS, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_JARVIS, value).apply()

    fun isConfigured(): Boolean = !aiApiKey.isNullOrBlank()

    companion object {
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_SEARCH_API_KEY = "search_api_key"
        private const val KEY_LANGUAGE = "preferred_language"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_WAKE_WORD = "wake_word_enabled"
        private const val KEY_CONFIRM_EVERY_ACTION = "confirm_every_action"
        private const val KEY_BACKGROUND_JARVIS = "background_jarvis_enabled"
    }
}
