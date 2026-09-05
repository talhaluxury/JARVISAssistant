package com.jarvis.assistant.hud

import android.content.Context
import com.jarvis.assistant.notifications.JarvisNotificationListenerService

/** Local, offline HUD actions. These never pretend that unavailable OS telemetry exists. */
object HudCommandExecutor {
    fun execute(context: Context, command: com.jarvis.assistant.command.JarvisCommand): String? {
        return when (command) {
            com.jarvis.assistant.command.JarvisCommand.ActivateHud -> {
                WallpaperEventBus.setMode(HudMode.FULL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "CORE", "HUD ACTIVE")
                "Holographic interface activated."
            }
            com.jarvis.assistant.command.JarvisCommand.StandbyHud -> {
                WallpaperEventBus.setMode(HudMode.STANDBY)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "CORE", "HUD STANDBY")
                "HUD standby activated."
            }
            com.jarvis.assistant.command.JarvisCommand.FullHud -> {
                WallpaperEventBus.setMode(HudMode.FULL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "CORE", "FULL HUD")
                "Full HUD activated."
            }
            com.jarvis.assistant.command.JarvisCommand.MinimalHud -> {
                WallpaperEventBus.setMode(HudMode.MINIMAL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "CORE", "MINIMAL HUD")
                "Minimal HUD activated."
            }
            com.jarvis.assistant.command.JarvisCommand.PowerSavingHud -> {
                WallpaperEventBus.setMode(HudMode.STANDBY)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "CORE", "POWER SAVING")
                "Power-saving HUD activated."
            }
            com.jarvis.assistant.command.JarvisCommand.ShowBattery -> {
                val t = DeviceTelemetryRepository(context).readNow()
                WallpaperEventBus.setMode(HudMode.FULL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "BATTERY", "BATTERY STATUS")
                if (t.batteryPercent == null) "Battery status is unavailable." else
                    "Battery is ${t.batteryPercent} percent${if (t.charging == true) " and charging" else ""}."
            }
            com.jarvis.assistant.command.JarvisCommand.ShowNetwork -> {
                val t = DeviceTelemetryRepository(context).readNow()
                WallpaperEventBus.setMode(HudMode.FULL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "NETWORK", "NETWORK STATUS")
                "Network is ${status(t.networkConnected)}. Wi-Fi is ${status(t.wifiConnected)}. Bluetooth is ${status(t.bluetoothEnabled)}."
            }
            com.jarvis.assistant.command.JarvisCommand.ShowNotificationsHud -> {
                val enabled = JarvisNotificationListenerService.isEnabled
                WallpaperEventBus.setMode(HudMode.FULL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "NOTIFICATIONS", "NOTIFICATION STATUS")
                if (!enabled) "Notification access is unavailable. Enable Notification Access in Android settings."
                else "There are ${JarvisNotificationListenerService.activeNotificationCount} active notifications."
            }
            com.jarvis.assistant.command.JarvisCommand.ShowSystemStatus -> {
                val t = DeviceTelemetryRepository(context).readNow()
                WallpaperEventBus.setMode(HudMode.FULL)
                WallpaperEventBus.emit(JarvisHudState.IDLE, "SYSTEM", "SYSTEM STATUS")
                val battery = t.batteryPercent?.let { "$it%" } ?: "UNAVAILABLE"
                val ram = if (t.ramUsedGb != null && t.ramTotalGb != null) "${fmt(t.ramUsedGb)} GB / ${fmt(t.ramTotalGb)} GB" else "UNAVAILABLE"
                val storage = if (t.storageUsedGb != null && t.storageTotalGb != null) "${fmt(t.storageUsedGb)} GB / ${fmt(t.storageTotalGb)} GB" else "UNAVAILABLE"
                "Battery $battery. RAM $ram. Storage $storage. Network ${status(t.networkConnected)}. Wi-Fi ${status(t.wifiConnected)}. Bluetooth ${status(t.bluetoothEnabled)}."
            }
            else -> null
        }
    }

    private fun status(v: Boolean?): String = when (v) {
        true -> "connected"
        false -> "off"
        null -> "unavailable"
    }

    private fun fmt(v: Float) = "%.1f".format(v)
}
