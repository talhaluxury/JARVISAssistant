package com.jarvis.assistant.hud

import android.content.Context

data class HudSettings(
    val brightness: Float = 0.9f,
    val animationIntensity: Float = 1f,
    val animationSpeed: Float = 1f,
    val showClock: Boolean = true,
    val showBattery: Boolean = true,
    val showRam: Boolean = true,
    val showStorage: Boolean = true,
    val showNetwork: Boolean = true,
    val showNotifications: Boolean = true,
    val showDevice: Boolean = true,
    val powerSaving: Boolean = false
)

class HudSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_hud_settings", Context.MODE_PRIVATE)

    fun read() = HudSettings(
        brightness = prefs.getFloat("brightness", .9f),
        animationIntensity = prefs.getFloat("intensity", 1f),
        animationSpeed = prefs.getFloat("speed", 1f),
        showClock = prefs.getBoolean("clock", true),
        showBattery = prefs.getBoolean("battery", true),
        showRam = prefs.getBoolean("ram", true),
        showStorage = prefs.getBoolean("storage", true),
        showNetwork = prefs.getBoolean("network", true),
        showNotifications = prefs.getBoolean("notifications", true),
        showDevice = prefs.getBoolean("device", true),
        powerSaving = prefs.getBoolean("powerSaving", false)
    )

    fun setBrightness(v: Float) = prefs.edit().putFloat("brightness", v.coerceIn(.1f, 1f)).apply()
    fun setIntensity(v: Float) = prefs.edit().putFloat("intensity", v.coerceIn(.25f, 1.5f)).apply()
    fun setSpeed(v: Float) = prefs.edit().putFloat("speed", v.coerceIn(.25f, 2f)).apply()
    fun setVisible(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun setPowerSaving(v: Boolean) = prefs.edit().putBoolean("powerSaving", v).apply()
}
