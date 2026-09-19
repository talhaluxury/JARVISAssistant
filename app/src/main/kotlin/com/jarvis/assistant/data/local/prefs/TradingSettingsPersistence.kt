package com.jarvis.assistant.data.local.prefs

import com.jarvis.assistant.trading.TradingSettings
import com.jarvis.assistant.trading.TradingSettingsStore
import kotlinx.serialization.json.Json

/**
 * PHASE 8 — TRADING SETTINGS PERSISTENCE
 *
 * The `com.jarvis.assistant.trading` package is deliberately plain Kotlin/JVM with zero Android
 * dependencies (every engine in Phases 1-7 is unit-tested without Robolectric or a Context).
 * This file is the one place that bridges it to Android storage — [SecurePrefs], the same
 * Keystore-backed encrypted store the rest of the app's settings already live in, rather than a
 * separate unencrypted file just for trading.
 *
 * Nothing sensitive (no broker credentials, no API keys) is stored here — this is purely
 * strategy configuration (demo/live flag, watchlist, risk limits). It goes in [SecurePrefs]
 * anyway for consistency with the rest of the app's settings, not because it strictly needs
 * encryption at rest.
 *
 * Corrupt or missing stored JSON always falls back to [TradingSettings]' own defaults (DEMO
 * mode, standard risk limits) rather than crashing or leaving the app in an undefined state —
 * spec §32's fail-closed philosophy applies to settings loading too: a bad read should never be
 * able to accidentally produce a MORE permissive configuration than the safe defaults.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun SecurePrefs.loadTradingSettings(): TradingSettings {
    val raw = tradingSettingsJson ?: return TradingSettings()
    return runCatching { json.decodeFromString(TradingSettings.serializer(), raw) }
        .getOrElse { TradingSettings() } // corrupt/old-schema JSON -> safe defaults, never a crash
}

fun SecurePrefs.saveTradingSettings(settings: TradingSettings) {
    tradingSettingsJson = json.encodeToString(TradingSettings.serializer(), settings)
}

/** One-line setup for app wiring: loads whatever was last saved (or defaults), and wires every
 * subsequent [TradingSettingsStore.update] to persist automatically. Callers never need to
 * remember to call [saveTradingSettings] themselves. */
fun createPersistentTradingSettingsStore(securePrefs: SecurePrefs): TradingSettingsStore =
    TradingSettingsStore(
        initial = securePrefs.loadTradingSettings(),
        onChange = { updated -> securePrefs.saveTradingSettings(updated) }
    )
