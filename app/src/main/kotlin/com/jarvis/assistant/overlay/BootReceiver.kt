package com.jarvis.assistant.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication

/**
 * Restarts the background JARVIS service after the phone reboots, but only if the user had
 * already turned "Background JARVIS" on in Settings and already granted the "display over
 * other apps" permission it needs — this never turns the feature on by itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val container = (context.applicationContext as JarvisApplication).container
        val wasEnabled = container.securePrefs.backgroundJarvisEnabled
        val canOverlay = Settings.canDrawOverlays(context)
        if (wasEnabled && canOverlay) {
            val serviceIntent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
