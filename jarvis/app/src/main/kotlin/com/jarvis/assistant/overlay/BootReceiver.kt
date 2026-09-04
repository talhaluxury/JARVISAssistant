package com.jarvis.assistant.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Android 14+ restricts starting a microphone foreground service from BOOT_COMPLETED.
 * We therefore do not force-start the microphone service after reboot on those releases.
 * The user can enable Background JARVIS again from Settings after unlocking the device.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as com.jarvis.assistant.JarvisApplication
        if (!app.container.securePrefs.backgroundJarvisEnabled) return
        if (Build.VERSION.SDK_INT >= 34) {
            Log.i("JARVIS", "Background microphone service will resume when the user opens/enables JARVIS after reboot.")
            return
        }
        runCatching {
            val serviceIntent = Intent(context, OverlayService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure { Log.w("JARVIS", "Could not resume background service after boot", it) }
    }
}
