package com.jarvis.assistant.demotrade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps the DEMO (paper) trading engine alive while Auto Demo Trading is ON, so Android does not
 * freeze the process while a demo trade is waiting for its expiry. It shows one ongoing notification with the engine status.
 *
 * It does NOT read the screen itself: prices still arrive through the Quotex monitoring service. When that service is not
 * running the notification says so ("waiting for Quotex data") instead of pretending the engine is trading.
 * DEMO / PAPER ONLY: nothing in here can place a real order.
 */
class DemoEngineService : Service() {
    private var scope: CoroutineScope? = null
    private var lastText = ""
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            switchAutoOff()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            startAsForeground(buildNotification("Starting", "Demo (paper) trading engine"))
        } catch (e: Exception) {
            // Android refused a foreground start from the background: the engine itself still works while the app lives.
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            val module = (application as JarvisApplication).container.demoTrading
            module.runtime // make sure the clock and the candle worker are running
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            s.launch {
                module.engine.state.collect { st ->
                    if (!st.settings.autoDemoTrading) {
                        stopSelf()
                    } else {
                        publish(describe(st))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun switchAutoOff() {
        try {
            (application as JarvisApplication).container.demoTrading.updateSettings { it.copy(autoDemoTrading = false) }
        } catch (e: Exception) {
            // nothing else to do: the service stops anyway
        }
    }

    private fun describe(st: DemoUiState): String {
        val money = String.format(Locale.US, "%.2f", st.equity)
        val pnl = String.format(Locale.US, "%+.2f", st.todayPnl)
        val feed = st.dataMessage
        return when {
            st.halt != HaltReason.NONE -> "Risk stop: ${st.halt.message}"
            feed != null && st.phase == EnginePhase.WAITING_FOR_DATA ->
                "Waiting for Quotex data - start Quotex monitoring. Equity $money"
            st.activeTrades.isNotEmpty() -> "${st.activeTrades.size} demo trade(s) open - equity $money, today $pnl"
            else -> "${st.phase.label} - equity $money, today $pnl"
        }
    }

    private fun publish(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastText || now - lastNotifyMs < MIN_NOTIFY_GAP_MS) return
        lastText = text
        lastNotifyMs = now
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification("DEMO engine running", text))
        } catch (e: SecurityException) {
            // notification permission revoked: the service keeps running silently
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DemoEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nPaper trading only - no real money."))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop auto demo trading", stop)
            .build()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Demo trading engine (paper)", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "jarvis_demo_engine"
        private const val NOTIFICATION_ID = 7100
        private const val ACTION_STOP = "com.jarvis.assistant.demotrade.STOP"
        private const val MIN_NOTIFY_GAP_MS = 2_000L

        /** Start the service while Auto Demo Trading is ON, stop it when it is OFF. Safe to call from anywhere. */
        fun sync(context: Context, autoOn: Boolean) {
            val app = context.applicationContext
            val intent = Intent(app, DemoEngineService::class.java)
            try {
                if (autoOn) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
            } catch (e: Exception) {
                // background start not allowed (Android 12+): ignored, the engine keeps working while the app process lives
            }
        }
    }
}
