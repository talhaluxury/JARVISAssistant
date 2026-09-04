package com.jarvis.assistant.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication

/**
 * Restarts the background JARVIS service after reboot if the user had already enabled
 * Background JARVIS. It never turns the feature on by itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val container = (context.applicationContext as JarvisApplication).container
        val wasEnabled = container.securePrefs.backgroundJarvisEnabled
        if (wasEnabled) {
            val serviceIntent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
