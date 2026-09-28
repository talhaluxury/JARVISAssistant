package com.jarvis.assistant.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.BuildConfig

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
        get() = prefs.getString(KEY_AI_API_KEY, null)?.takeUnless { it.isBlank() } ?: BuildConfig.DEFAULT_AI_API_KEY.ifBlank { null }
        set(value) = prefs.edit().putString(KEY_AI_API_KEY, value).apply()

    var aiBaseUrl: String?
        get() = prefs.getString(KEY_AI_BASE_URL, null)?.takeUnless { it.isBlank() } ?: BuildConfig.DEFAULT_AI_BASE_URL.ifBlank { null }
        set(value) = prefs.edit().putString(KEY_AI_BASE_URL, value).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, null)?.takeUnless { it.isBlank() } ?: BuildConfig.DEFAULT_AI_MODEL.ifBlank { "gpt-4o-mini" }
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value).apply()

    /**
     * What the owner has actually typed and saved in Settings themselves - null/blank if
     * they haven't, even when a baked-in default is silently in effect via [aiApiKey] etc.
     * The Settings screen shows these (so the box stays empty until the owner types their
     * own key), never [aiApiKey]/[aiBaseUrl]/[aiModel] directly - those are for actual API
     * calls only and would otherwise leak the baked-in key into the visible UI.
     */
    val aiApiKeyRaw: String? get() = prefs.getString(KEY_AI_API_KEY, null)?.takeUnless { it.isBlank() }
    val aiBaseUrlRaw: String? get() = prefs.getString(KEY_AI_BASE_URL, null)?.takeUnless { it.isBlank() }
    val aiModelRaw: String? get() = prefs.getString(KEY_AI_MODEL, null)?.takeUnless { it.isBlank() }

    /** True if a key is actually in effect right now, whether the owner typed it or it's the baked-in default. */
    fun hasEffectiveApiKey(): Boolean = !aiApiKey.isNullOrBlank()

    var searchApiKey: String?
        get() = prefs.getString(KEY_SEARCH_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_SEARCH_API_KEY, value).apply()

    var preferredLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value).apply()

    /** Below 1.0 = deeper/heavier voice. Defaults lower than normal for a JARVIS-style tone. */
    var voicePitch: Float
        get() = prefs.getFloat(KEY_VOICE_PITCH, 0.82f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_PITCH, value).apply()

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

    /** Whether the background JARVIS foreground service should be running. */
    var backgroundJarvisEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_JARVIS, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_JARVIS, value).apply()

    /** Master switch for on-screen automation (tapping/typing/scrolling in other apps). Turning
     * this off still allows plain navigation (back/home/recents) but blocks AUTOMATE, search-in-app,
     * WhatsApp sending, etc. — a quick "phone control" kill switch independent of Accessibility. */
    var screenAutomationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_AUTOMATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_AUTOMATION, value).apply()

    /** How many silent-follow-up turns a wake-word/task session stays open for before JARVIS
     * falls back to needing the wake word again. */
    var conversationSessionTurns: Int
        get() = prefs.getInt(KEY_CONVERSATION_TURNS, 4)
        set(value) = prefs.edit().putInt(KEY_CONVERSATION_TURNS, value.coerceIn(1, 10)).apply()

    fun isConfigured(): Boolean = !aiApiKey.isNullOrBlank()

    /** #16 JARVIS MODES — SILENT: JARVIS still executes and replies in text, but never speaks
     * out loud. Distinct from muting the device — this is JARVIS choosing not to use TTS. */
    var silentModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SILENT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_MODE, value).apply()

    /** #16 JARVIS MODES — FOCUS: suppresses JARVIS's own non-essential notifications (e.g.
     * routine "command completed" pings) while still executing everything normally. */
    var focusModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_FOCUS_MODE, value).apply()

    /** Developer-only dry run. Plans and logs actions but never calls Android executors. */
    var developerSimulationEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_SIMULATION, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER_SIMULATION, value).apply()

    /** Safe mode blocks autonomous execution until explicitly disabled by the user. */
    var safeModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAFE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SAFE_MODE, value).apply()

    /** Local retention window for operational logs, in days. */
    var auditRetentionDays: Int
        get() = prefs.getInt(KEY_AUDIT_RETENTION_DAYS, 30)
        set(value) = prefs.edit().putInt(KEY_AUDIT_RETENTION_DAYS, value.coerceIn(1, 3650)).apply()

    /** Raw JSON blob for the forex trading module's settings (demo/live mode, watchlist, risk
     * limits) — see com.jarvis.assistant.data.local.prefs.TradingSettingsPersistence for the
     * (de)serialization. Stored as one opaque string rather than one key per field so adding a
     * new trading setting later never requires a SecurePrefs migration. Null until the trading
     * module has saved something at least once, in which case callers fall back to defaults. */
    var tradingSettingsJson: String?
        get() = prefs.getString(KEY_TRADING_SETTINGS_JSON, null)
        set(value) = prefs.edit().putString(KEY_TRADING_SETTINGS_JSON, value).apply()

    /** OANDA v20 API credentials for the (Phase 9) live/practice forex broker adapter. Entered
     * once in Trading Settings, never bundled in the APK — same Keystore-backed storage as the
     * AI API key above. Null/blank means no broker is connected yet. */
    var oandaApiKey: String?
        get() = prefs.getString(KEY_OANDA_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_OANDA_API_KEY, value).apply()

    var oandaAccountId: String?
        get() = prefs.getString(KEY_OANDA_ACCOUNT_ID, null)
        set(value) = prefs.edit().putString(KEY_OANDA_ACCOUNT_ID, value).apply()

    /** True = OANDA's fxPractice (paper) environment, false = real-money fxTrade. Defaults to
     * practice — same "fail toward the safe default" reasoning as everything else in the
     * trading module defaulting to demo/paper. */
    var oandaUsePracticeEnvironment: Boolean
        get() = prefs.getBoolean(KEY_OANDA_PRACTICE_ENV, true)
        set(value) = prefs.edit().putBoolean(KEY_OANDA_PRACTICE_ENV, value).apply()

    fun hasOandaCredentials(): Boolean = !oandaApiKey.isNullOrBlank() && !oandaAccountId.isNullOrBlank()

    companion object {
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_SEARCH_API_KEY = "search_api_key"
        private const val KEY_LANGUAGE = "preferred_language"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_VOICE_PITCH = "voice_pitch"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_WAKE_WORD = "wake_word_enabled"
        private const val KEY_CONFIRM_EVERY_ACTION = "confirm_every_action"
        private const val KEY_BACKGROUND_JARVIS = "background_jarvis_enabled"
        private const val KEY_SCREEN_AUTOMATION = "screen_automation_enabled"
        private const val KEY_CONVERSATION_TURNS = "conversation_session_turns"
        private const val KEY_SILENT_MODE = "silent_mode_enabled"
        private const val KEY_FOCUS_MODE = "focus_mode_enabled"
        private const val KEY_DEVELOPER_SIMULATION = "developer_simulation_enabled"
        private const val KEY_SAFE_MODE = "safe_mode_enabled"
        private const val KEY_AUDIT_RETENTION_DAYS = "audit_retention_days"
        private const val KEY_TRADING_SETTINGS_JSON = "trading_settings_json"
        private const val KEY_OANDA_API_KEY = "oanda_api_key"
        private const val KEY_OANDA_ACCOUNT_ID = "oanda_account_id"
        private const val KEY_OANDA_PRACTICE_ENV = "oanda_practice_environment"
    }
}
