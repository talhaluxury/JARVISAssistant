package com.jarvis.assistant.trading

import java.util.concurrent.atomic.AtomicReference

/**
 * PHASE 7/8 — RUNTIME SETTINGS HOLDER
 *
 * [TradingSettings] itself is an immutable data class (by design — every engine in Phases 1-6
 * takes it as a plain parameter, never mutates it). Voice commands like "enable demo trading"
 * need somewhere to actually flip [TradingSettings.tradingMode] at runtime; this is that
 * somewhere. [AtomicReference] rather than a plain var: [ForexBrainCommandExecutor] methods are
 * suspend functions that may be invoked from different coroutines (voice recognition callback vs
 * a background scanning loop), and settings reads/writes must never tear.
 *
 * [onChange] (Phase 8) is how persistence gets wired in WITHOUT this class importing anything
 * Android — the whole `trading` package runs and is unit-tested as plain Kotlin/JVM with no
 * Context, no SharedPreferences, no Robolectric. `com.jarvis.assistant.data.local.prefs`'s
 * TradingSettingsPersistence.kt supplies the actual save-to-disk callback and the initial
 * loaded value; this class only calls what it's given.
 */
class TradingSettingsStore(
    initial: TradingSettings = TradingSettings(),
    private val onChange: ((TradingSettings) -> Unit)? = null
) {
    private val ref = AtomicReference(initial)

    fun current(): TradingSettings = ref.get()

    fun update(transform: (TradingSettings) -> TradingSettings) {
        val updated = ref.updateAndGet(transform)
        onChange?.invoke(updated)
    }
}
