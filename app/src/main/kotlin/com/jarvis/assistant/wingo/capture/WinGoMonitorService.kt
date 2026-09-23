package com.jarvis.assistant.wingo.capture

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
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.WinGoModule
import com.jarvis.assistant.wingo.domain.NormalizedRegion
import com.jarvis.assistant.wingo.ocr.GameRegionDetector
import com.jarvis.assistant.wingo.ocr.MlKitTextReader
import com.jarvis.assistant.wingo.ocr.ResultStabilizer
import com.jarvis.assistant.wingo.ocr.ResultValidator
import com.jarvis.assistant.wingo.ocr.RowStatus
import com.jarvis.assistant.wingo.ocr.WinGoOCRParser
import com.jarvis.assistant.wingo.ocr.FrameDiff
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
 * Foreground service that reads the WinGo history table from the screen the user approved for capture.
 * Read-only: it never taps, swipes, types or otherwise controls the game. A visible notification
 * (with a Stop button) is shown for as long as capture is running.
 */
class WinGoMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var capture: ScreenCaptureManager? = null
    private var reader: MlKitTextReader? = null
    private lateinit var module: WinGoModule

    override fun onCreate() {
        super.onCreate()
        module = (applicationContext as JarvisApplication).container.winGo
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                if (!begin(intent)) stopSelf()
            }
            else -> stopSelf() // ACTION_STOP or anything unexpected
        }
        return START_NOT_STICKY // a MediaProjection token cannot be reused after a restart
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
            val manager2 = ScreenCaptureManager(this, projection) { stopSelf() }
            manager2.start()
            capture = manager2
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
        val detector = GameRegionDetector(periodLength = config.periodLength)
        val parser = WinGoOCRParser()
        val validator = ResultValidator(config)
        val stabilizer = ResultStabilizer(config.confirmations)

        val savedManual: NormalizedRegion? = settings.manualRegion
        // A region shorter than this can never show the whole history table - ignore it rather than get
        // permanently stuck on a sliver that was saved by mistake (e.g. sliders dragged past each other).
        val manual: NormalizedRegion? = savedManual?.takeIf { it.bottom - it.top >= 0.15f }
        if (savedManual != null && manual == null) coordinator.setMessage("Saved history region was too small; using auto-detect instead.")
        var region: NormalizedRegion? = manual
        var lastSignature: IntArray? = null
        var lastOcrAt = 0L
        var misses = 0
        var detected = false

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
                val activeRegion = region
                if (activeRegion == null) {
                    // SEARCHING: slow full-frame pass looking for the history table.
                    val lines = textReader.read(frame)
                    val detection = detector.detect(lines, frame.width, frame.height)
                    if (detection.visible && detection.historyRegion != null) {
                        region = detection.historyRegion
                        stabilizer.clear()
                        lastSignature = null
                        misses = 0
                        detected = true
                        coordinator.setScreenStatus(ScreenStatus.TRACKING)
                        pause = config.sampleIntervalMs
                    } else {
                        coordinator.setScreenStatus(ScreenStatus.NOT_DETECTED)
                        pause = config.searchIntervalMs
                    }
                } else {
                    val crop = ScreenCaptureManager.crop(frame, activeRegion)
                    try {
                        val signature = ScreenCaptureManager.signature(crop)
                        val now = System.currentTimeMillis()
                        val changed = FrameDiff.differs(lastSignature, signature, HASH_THRESHOLD)
                        val stale = now - lastOcrAt > config.forcedRefreshMs
                        if (changed || stale || stabilizer.hasPending()) {
                            lastSignature = signature
                            lastOcrAt = now
                            val ocrLines = textReader.read(crop)
                            val rows = parser.parse(ocrLines)
                            val pager = parser.parsePager(ocrLines)
                            if (pager != null) coordinator.setPage(pager.first, pager.second)
                            val verdicts = validator.validateBatch(rows, now)
                            val dataRows = rows.count { it.period != null }
                            if (dataRows == 0) misses++ else misses = 0
                            coordinator.noteUncertain(verdicts.count { it.status == RowStatus.UNCERTAIN })
                            val trusted = verdicts.mapNotNull { if (it.status == RowStatus.TRUSTED) it.result else null }
                            val confirmed = stabilizer.offer(trusted)
                            if (confirmed.isNotEmpty()) coordinator.onConfirmedResults(confirmed)
                            val newestVisible = trusted.maxOfOrNull { it.period }
                            if (newestVisible != null) coordinator.onVisiblePeriods(newestVisible)

                            if (misses >= config.missesBeforePause) {
                                // Game screen went away: pause analysis and go back to looking for it.
                                coordinator.setScreenStatus(ScreenStatus.NOT_DETECTED)
                                detected = false
                                if (manual == null) region = null
                                pause = config.searchIntervalMs
                            } else if (!detected && dataRows > 0) {
                                detected = true
                                coordinator.setScreenStatus(ScreenStatus.TRACKING)
                            }
                        }
                    } finally {
                        crop.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coordinator.setMessage("Unable to verify game result.")
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
            this, 1,
            Intent(this, WinGoMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WinGo Analyzer: screen capture ON")
            .setContentText("Reading the game history on your screen. Tap Stop to end.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "WinGo Analyzer", NotificationManager.IMPORTANCE_LOW)
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
        private const val ACTION_START = "com.jarvis.assistant.wingo.START"
        private const val ACTION_STOP = "com.jarvis.assistant.wingo.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "wingo_monitor"
        private const val NOTIFICATION_ID = 7421
        private const val HASH_THRESHOLD = 3.0

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, WinGoMonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, WinGoMonitorService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Service is not running; nothing to stop.
            }
        }
    }
}
