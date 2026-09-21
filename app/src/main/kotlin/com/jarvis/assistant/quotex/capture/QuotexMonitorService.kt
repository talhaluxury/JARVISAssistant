package com.jarvis.assistant.quotex.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.R
import com.jarvis.assistant.quotex.QuotexModule
import com.jarvis.assistant.quotex.ocr.QuotexScreenParser
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.capture.ScreenCaptureManager
import com.jarvis.assistant.wingo.domain.NormalizedRegion
import com.jarvis.assistant.wingo.ocr.MlKitTextReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that reads the asset name and live price from the Quotex chart on the screen the
 * user approved for capture. Read-only: it never taps, swipes or types, and cannot place a trade.
 * A visible notification (with a Stop button) is shown while capture runs.
 */
class QuotexMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var capture: ScreenCaptureManager? = null
    private var reader: MlKitTextReader? = null
    private lateinit var module: QuotexModule

    override fun onCreate() {
        super.onCreate()
        module = (applicationContext as JarvisApplication).container.quotex
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                if (!begin(intent)) stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent): Boolean {
        if (loopJob?.isActive == true) return true
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            module.coordinator.setMessage("Screen capture was not approved.")
            return false
        }
        return try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            val screen = ScreenCaptureManager(this, projection) { stopSelf() }
            screen.start()
            capture = screen
            reader = MlKitTextReader()
            module.coordinator.setMonitor(true)
            loopJob = scope.launch { monitorLoop() }
            true
        } catch (e: Exception) {
            module.coordinator.setMonitor(false)
            module.coordinator.setMessage("Could not start screen capture.")
            false
        }
    }

    private suspend fun monitorLoop() {
        val coordinator = module.coordinator
        val settings = module.settings
        val config = settings.config()
        val textReader = reader ?: return
        val parser = QuotexScreenParser()
        var misses = 0

        coordinator.ensureReady()
        coordinator.setScreenStatus(ScreenStatus.SEARCHING)

        while (currentCoroutineContext().isActive) {
            var pause = config.sampleIntervalMs
            try {
                val frame = capture?.latestFrame()
                if (frame == null) {
                    delay(config.searchIntervalMs)
                    continue
                }
                val top = settings.regionTop.coerceIn(0f, 0.9f)
                val bottom = settings.regionBottom.coerceIn(top + 0.05f, 1f)
                val crop = ScreenCaptureManager.crop(frame, NormalizedRegion(0f, top, 1f, bottom))
                try {
                    val lines = textReader.read(crop)
                    val reading = parser.parse(lines, crop.width, crop.height)
                    if (reading.price != null) {
                        misses = 0
                        coordinator.setScreenStatus(ScreenStatus.TRACKING)
                    } else {
                        misses++
                        if (misses >= config.missesBeforePause) {
                            coordinator.setScreenStatus(ScreenStatus.NOT_DETECTED)
                            pause = config.searchIntervalMs
                        }
                    }
                    coordinator.onReading(reading)
                } finally {
                    crop.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coordinator.setMessage("Unable to read the Quotex chart.")
                pause = config.searchIntervalMs
            }
            delay(pause)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, QuotexMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Quotex Analyzer: screen capture ON")
            .setContentText("Reading the chart price on your screen. Tap Stop to end.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Quotex Analyzer", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        try { reader?.close() } catch (e: Exception) { }
        capture?.close()
        capture = null
        reader = null
        if (::module.isInitialized) module.coordinator.setMonitor(false)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.jarvis.assistant.quotex.START"
        private const val ACTION_STOP = "com.jarvis.assistant.quotex.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "quotex_monitor"
        private const val NOTIFICATION_ID = 7422

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, QuotexMonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, QuotexMonitorService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Not running.
            }
        }
    }
}
