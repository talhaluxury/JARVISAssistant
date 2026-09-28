package com.jarvis.assistant.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R

/**
 * Keeps this phone connected to the relay server in the background, WITHOUT
 * screen mirroring - so remote commands (GET LOCATION, LOCK PHONE, WAKE
 * SCREEN, and touch commands if Accessibility is on) keep working even when
 * the owner hasn't manually started "START + SHARE SCREEN".
 *
 * Screen mirroring itself still always needs a fresh manual tap on Android's
 * own "Start recording or casting?" system dialog every time - that consent
 * cannot be automated or skipped, by Android design (it exists specifically
 * so screen content can never be captured without the phone's own user
 * seeing and approving it in the moment).
 */
class RemoteConnectService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            4902,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("JARVIS remote connected")
                .setContentText("Ready for remote commands. Turn off in JARVIS Remote settings.")
                .setOngoing(true)
                .build()
        )
        RemoteSession.start()
        RemoteRelayClient.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Only fully stop the relay if screen sharing isn't the one keeping it alive.
        if (!RemoteSession.active) RemoteRelayClient.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "JARVIS Remote", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_remote"
    }
}
