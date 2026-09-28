package com.jarvis.assistant.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.data.local.prefs.SecurePrefs

/**
 * #25 NOTIFICATIONS
 *
 * Deliberately narrow: only the two cases the spec actually names (a completed multi-step
 * task, and a real low-battery reading) — never one per single quick command, so this can't
 * turn into the spam the spec explicitly warns against. Every call respects Focus Mode, and
 * every call checks the real POST_NOTIFICATIONS permission state rather than assuming it.
 */
object JarvisNotifier {
    private const val CHANNEL_ID = "jarvis_alerts"
    private const val TASK_NOTIFICATION_ID = 2001
    private const val BATTERY_NOTIFICATION_ID = 2002
    private const val LOW_BATTERY_THRESHOLD = 15

    @Volatile private var lowBatteryWarned = false

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "JARVIS Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun canNotify(context: Context, securePrefs: SecurePrefs): Boolean {
        if (securePrefs.focusModeEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** Fired only for a completed multi-step task — never for a single quick command, so this
     * never becomes a notification per message. */
    fun notifyTaskCompleted(context: Context, securePrefs: SecurePrefs, summary: String) {
        if (!canNotify(context, securePrefs)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS")
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(TASK_NOTIFICATION_ID, notification) }
    }

    /** Debounced: fires once when battery drops below the threshold while unplugged, then
     * stays quiet until the level recovers above it (or charging starts) before it can fire
     * again — never one warning per telemetry tick. */
    fun maybeNotifyLowBattery(context: Context, securePrefs: SecurePrefs, batteryPercent: Int?, charging: Boolean) {
        if (batteryPercent == null) return
        if (charging || batteryPercent > LOW_BATTERY_THRESHOLD) {
            lowBatteryWarned = false
            return
        }
        if (lowBatteryWarned) return
        if (!canNotify(context, securePrefs)) return
        lowBatteryWarned = true
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS")
            .setContentText("Battery level: $batteryPercent%. Power connection recommended.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(BATTERY_NOTIFICATION_ID, notification) }
    }
}
