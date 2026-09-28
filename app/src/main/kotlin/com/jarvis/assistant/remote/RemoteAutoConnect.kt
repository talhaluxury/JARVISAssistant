package com.jarvis.assistant.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether JARVIS should keep itself connected to the relay server in the
 * background automatically (without the owner needing to press
 * "START + SHARE SCREEN" first), so remote commands like GET LOCATION and
 * LOCK PHONE are always ready. See [RemoteConnectService].
 */
object RemoteAutoConnect {
    private const val PREFS = "jarvis_remote_autoconnect"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop(context)
    }

    fun start(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, RemoteConnectService::class.java)
            )
        } catch (_: Exception) {
            // e.g. Android 14+ blocks starting some foreground services from the
            // background (such as right after boot) - the owner can just open
            // the app once to (re)start it in that case.
        }
    }

    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, RemoteConnectService::class.java))
        } catch (_: Exception) {}
        if (!RemoteSession.active) RemoteRelayClient.stop()
    }

    /** Call from BOOT_COMPLETED. Mirrors the existing Background-JARVIS boot behavior. */
    fun startOnBootIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        if (Build.VERSION.SDK_INT >= 34) return // see BootReceiver's existing note on this restriction
        start(context)
    }
}
