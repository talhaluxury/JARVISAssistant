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
import com.jarvis.assistant.quotex.domain.Candle
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
 * user approved for capture. It never taps or types and cannot place a trade. The only gesture it can make is an
 * opt-in horizontal chart drag (JarvisAccessibilityService.panChart) used to load older candles.
 * A visible notification (with a Stop button) is shown while capture runs.
 */
class QuotexMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var capture: ScreenCaptureManager? = null
    private var reader: MlKitTextReader? = null
    private lateinit var module: QuotexModule
    @Volatile private var backfillRequested = false

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
            ACTION_BACKFILL -> {
                if (loopJob?.isActive == true) backfillRequested = true
                else module.coordinator.setMessage("Start Quotex monitoring first, then load the chart history.")
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
        var config = settings.config()
        val textReader = reader ?: return
        val parser = QuotexScreenParser()
        var misses = 0
        var tfCandidate = 0
        var tfHits = 0
        var lastAutoBackfillAt = 0L
        val countdownTf = com.jarvis.assistant.quotex.ocr.CountdownTimeframe()
        var mismatchFrames = 0
        var lastLiveDragAt = 0L

        coordinator.ensureReady()
        coordinator.setScreenStatus(ScreenStatus.SEARCHING)

        while (currentCoroutineContext().isActive) {
            var pause = if (config.candleSeconds <= 15) FAST_SAMPLE_MS else config.sampleIntervalMs
            if (backfillRequested) {
                backfillRequested = false
                try {
                    runBackfill(textReader, parser)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    coordinator.setMessage("Chart history failed: ${e.javaClass.simpleName}.")
                }
                continue
            }
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
                    val sig = ScreenCaptureManager.signature(crop)
                    val blank = ((sig.maxOrNull() ?: 0) - (sig.minOrNull() ?: 0)) < 6
                    val lines = if (blank) emptyList() else textReader.read(crop)
                    val parsed = parser.parse(lines, crop.width, crop.height)
                    val reading = if (blank) {
                        parsed.copy(note = "The captured screen is blank/black. Quotex may block screen capture, or another screen is being shared.")
                    } else {
                        parsed
                    }
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
                    if (reading.price != null && settings.useChartCandles) {
                        val detection = detectChart(crop, reading, config.candleMs)
                        coordinator.onChartDetection(detection, reading.price)
                        // Chart scrolled back (e.g. after history loading or a stray touch): newest chart candle no longer matches
                        // the live price for several frames in a row -> drag it back to the live edge by itself.
                        if (detection.candles.isNotEmpty()) {
                            val agrees = com.jarvis.assistant.quotex.ocr.ChartCandleDetector.agreesWith(detection, reading.price, reading.price * HISTORY_PRICE_TOLERANCE)
                            mismatchFrames = if (agrees) 0 else mismatchFrames + 1
                        }
                        val nowMs = System.currentTimeMillis()
                        if (mismatchFrames >= LIVE_DRAG_MISMATCH_FRAMES && settings.autoChartPan && !backfillRequested &&
                            com.jarvis.assistant.accessibility.JarvisAccessibilityService.isEnabled && nowMs - lastLiveDragAt > LIVE_DRAG_COOLDOWN_MS
                        ) {
                            lastLiveDragAt = nowMs
                            mismatchFrames = 0
                            val dm = resources.displayMetrics
                            val ax = (reading.axisLeftX ?: (dm.widthPixels * 0.8f).toInt()).toFloat()
                            repeat(3) {
                                com.jarvis.assistant.accessibility.JarvisAccessibilityService.panChart(ax * 0.75f, ax * 0.10f, dm.heightPixels * 0.33f)
                                delay(PAN_SETTLE_MS)
                            }
                            coordinator.setMessage("Chart was not at the live edge - dragged it back to the newest candle.")
                        }
                        // Fully automatic history: chart readable + too few candles -> load history without any tap.
                        val ui = coordinator.state.value
                        val now = System.currentTimeMillis()
                        if (settings.autoLoadHistory && detection.candles.isNotEmpty() && ui.asset != null &&
                            ui.candleCount < config.minCandlesForSignal && now - lastAutoBackfillAt > AUTO_BACKFILL_COOLDOWN_MS
                        ) {
                            lastAutoBackfillAt = now
                            if (settings.autoChartPan && com.jarvis.assistant.accessibility.JarvisAccessibilityService.isEnabled) {
                                coordinator.setMessage("History: only ${ui.candleCount}/${config.minCandlesForSignal} candles - loading chart history automatically...")
                                backfillRequested = true
                            } else {
                                coordinator.setMessage("History is short (${ui.candleCount}/${config.minCandlesForSignal}). Turn ON the JARVIS Accessibility service so it can scroll the chart by itself.")
                            }
                        }
                        // The broker's own countdown chip is exact; chart spacing is only a fallback when it is unreadable.
                        val exact = countdownTf.add(reading.countdownSec)
                        val seen = exact ?: if (reading.countdownSec == null) timeframeFrom(reading, detection) else null
                        if (seen != null && seen == tfCandidate) tfHits++ else { tfCandidate = seen ?: 0; tfHits = if (seen != null) 1 else 0 }
                        val needed = if (exact != null) 2 else TF_HITS_NEEDED * 4
                        if (seen != null && tfHits >= needed && seen != settings.candleSeconds) {
                            settings.candleSeconds = seen
                            coordinator.onTimeframeChanged()
                            config = settings.config()
                            coordinator.setMessage("Chart timeframe read from the screen: ${seen}s. History restarted on the new candle length.")
                            tfHits = 0
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

    /** Reads candles from the chart pixels of this frame. A failure is returned with its reason, never thrown. */
    private fun detectChart(crop: android.graphics.Bitmap, reading: com.jarvis.assistant.quotex.ocr.QuotexReading, candleMs: Long, rightEdgeOnly: Boolean = true): com.jarvis.assistant.quotex.ocr.ChartDetection {
        fun fail(why: String) = com.jarvis.assistant.quotex.ocr.ChartDetection(emptyList(), 0.0, why)
        return try {
            val axisLeft = reading.axisLeftX ?: return fail("price-axis position not found")
            val calibration = com.jarvis.assistant.quotex.ocr.PriceAxisCalibration.fit(reading.gridLabels, minLabels = 2)
                ?: return fail("price scale not calibrated (${reading.gridLabels.size} grid labels, need 2+ on one line)")
            val w = crop.width
            val h = crop.height
            val pixels = IntArray(w * h)
            crop.getPixels(pixels, 0, w, 0, 0, w, h)
            val rightmost = System.currentTimeMillis() / candleMs * candleMs
            com.jarvis.assistant.quotex.ocr.ChartCandleDetector().detect(pixels, w, h, (axisLeft - 4).coerceAtLeast(1), calibration, rightmost, candleMs, rightEdgeOnly, reading.timeAxisTopY ?: h)
        } catch (e: Exception) {
            fail("bitmap unreadable (${e.javaClass.simpleName})")
        }
    }

    /**
     * Candle length of the chart itself: candle spacing in pixels divided by pixels per minute of the time axis,
     * snapped to a standard timeframe. Null unless both were read cleanly and the match is within 25%.
     */
    private fun timeframeFrom(reading: com.jarvis.assistant.quotex.ocr.QuotexReading, det: com.jarvis.assistant.quotex.ocr.ChartDetection): Int? {
        val pxPerMin = reading.pxPerMinute ?: return null
        if (det.candles.size < 5 || det.pitchPx <= 0.0 || det.confidence < HISTORY_MIN_CONFIDENCE) return null
        val seconds = det.pitchPx / pxPerMin * 60.0
        val best = STANDARD_TIMEFRAMES.minByOrNull { kotlin.math.abs(kotlin.math.ln(seconds / it)) } ?: return null
        return if (kotlin.math.abs(seconds - best) <= best * 0.25) best else null
    }

    private class HistoryFrame(val reading: com.jarvis.assistant.quotex.ocr.QuotexReading, val detection: com.jarvis.assistant.quotex.ocr.ChartDetection)

    /** One capture of the screen, read with OCR and the chart detector. Null when there is no frame yet. */
    private suspend fun readHistoryFrame(textReader: MlKitTextReader, parser: QuotexScreenParser, candleMs: Long, rightEdgeOnly: Boolean): HistoryFrame? {
        val frame = capture?.latestFrame() ?: return null
        val settings = module.settings
        val top = settings.regionTop.coerceIn(0f, 0.9f)
        val bottom = settings.regionBottom.coerceIn(top + 0.05f, 1f)
        val crop = ScreenCaptureManager.crop(frame, NormalizedRegion(0f, top, 1f, bottom))
        try {
            val sig = ScreenCaptureManager.signature(crop)
            if (((sig.maxOrNull() ?: 0) - (sig.minOrNull() ?: 0)) < 6) return null
            val reading = parser.parse(textReader.read(crop), crop.width, crop.height)
            return HistoryFrame(reading, detectChart(crop, reading, candleMs, rightEdgeOnly))
        } finally {
            crop.recycle()
        }
    }

    /**
     * Loads older candles: reads the live chart, scrolls the chart back in time (by itself if the user allowed
     * chart panning, otherwise the user drags it), reads each new screen and stitches the overlaps together.
     * Only chart candles whose overlap with what is already known is exact are kept.
     */
    private suspend fun runBackfill(textReader: MlKitTextReader, parser: QuotexScreenParser) {
        val coordinator = module.coordinator
        val settings = module.settings
        var config = settings.config()
        val auto = settings.autoChartPan && com.jarvis.assistant.accessibility.JarvisAccessibilityService.isEnabled
        val target = config.minCandlesForSignal + 50
        var stitcher = com.jarvis.assistant.quotex.ocr.HistoryStitcher(config.candleMs)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        val startedAt = System.currentTimeMillis()
        com.jarvis.assistant.quotex.overlay.QuotexOverlayService.hide(this)
        var pans = 0
        var axisLeft = screenW * 0.8f
        try {
            coordinator.setMessage("History: switch to the Quotex chart now (live view, price axis visible). Reading starts in a moment...")
            // 1) The live frame seeds the history. Wait up to 40 s for the user to switch to the chart.
            var seeded = false
            var lastWhy = "no frame"
            while (!seeded && currentCoroutineContext().isActive && System.currentTimeMillis() - startedAt < SEED_WAIT_MS) {
                delay(1200)
                val fr = readHistoryFrame(textReader, parser, config.candleMs, rightEdgeOnly = true) ?: continue
                val price = fr.reading.price
                val det = fr.detection
                lastWhy = det.note
                val seenTf = if (fr.reading.countdownSec == null) timeframeFrom(fr.reading, det) else null
                if (seenTf != null && seenTf != config.candleSeconds) {
                    // The chart's own timeframe differs from JARVIS's: follow the chart, then read the frame again.
                    settings.candleSeconds = seenTf
                    coordinator.onSettingsChanged()
                    config = settings.config()
                    stitcher = com.jarvis.assistant.quotex.ocr.HistoryStitcher(config.candleMs)
                    coordinator.setMessage("History: chart timeframe is ${seenTf}s - JARVIS candle length set automatically.")
                    continue
                }
                if (price != null && det.candles.isNotEmpty() && det.confidence >= HISTORY_MIN_CONFIDENCE &&
                    com.jarvis.assistant.quotex.ocr.ChartCandleDetector.agreesWith(det, price, price * HISTORY_PRICE_TOLERANCE)
                ) {
                    stitcher.seed(det.candles)
                    fr.reading.axisLeftX?.let { axisLeft = it.toFloat() }
                    seeded = true
                }
            }
            if (!seeded) {
                coordinator.setMessage("History: could not read the live chart ($lastWhy). Open the chart at normal zoom and try again.")
                return
            }

            // 2) Scroll back and stitch.
            var stalls = 0
            var lastGrowthAt = System.currentTimeMillis()
            while (currentCoroutineContext().isActive && stitcher.size < target) {
                val now = System.currentTimeMillis()
                if (now - startedAt > MAX_BACKFILL_MS) break
                if (auto) {
                    if (pans >= MAX_PANS) break
                    val ok = com.jarvis.assistant.accessibility.JarvisAccessibilityService.panChart(axisLeft * 0.15f, axisLeft * 0.55f, screenH * 0.33f)
                    if (!ok) { coordinator.setMessage("History: the chart scroll was refused by Android (is the Accessibility service on and the chart on screen?). Turn auto-scroll off and drag the chart yourself."); break }
                    pans++
                    delay(PAN_SETTLE_MS)
                } else {
                    coordinator.setMessage("History: ${stitcher.size}/$target candles. Drag the chart slowly to the RIGHT (older candles); stop when you reach the end.")
                    delay(MANUAL_POLL_MS)
                }
                var fr = readHistoryFrame(textReader, parser, config.candleMs, rightEdgeOnly = false)
                var res = if (fr == null || fr.detection.candles.isEmpty()) null else stitcher.add(fr.detection.candles)
                if (auto && res == com.jarvis.assistant.quotex.ocr.HistoryStitcher.Result.NO_OVERLAP) {
                    // The chart coasted too far (inertia) and skipped past the overlap: come back a little and look again.
                    for (retry in 0 until 2) {
                        com.jarvis.assistant.accessibility.JarvisAccessibilityService.panChart(axisLeft * 0.50f, axisLeft * 0.30f, screenH * 0.33f)
                        delay(PAN_SETTLE_MS)
                        fr = readHistoryFrame(textReader, parser, config.candleMs, rightEdgeOnly = false)
                        res = if (fr == null || fr.detection.candles.isEmpty()) null else stitcher.add(fr.detection.candles)
                        if (res == com.jarvis.assistant.quotex.ocr.HistoryStitcher.Result.EXTENDED || res == com.jarvis.assistant.quotex.ocr.HistoryStitcher.Result.NO_NEW_CANDLES) break
                    }
                }
                fr?.reading?.axisLeftX?.let { axisLeft = it.toFloat() }
                if (res == com.jarvis.assistant.quotex.ocr.HistoryStitcher.Result.EXTENDED) {
                    stalls = 0
                    lastGrowthAt = System.currentTimeMillis()
                    coordinator.setMessage("History: ${stitcher.size}/$target candles read...")
                } else {
                    stalls++
                    // Quotex loads older candles lazily after a scroll: give it a moment before counting a dead end.
                    if (auto) delay(STALL_RETRY_MS)
                }
                if (auto && stalls >= MAX_STALLS) break
                if (!auto && System.currentTimeMillis() - lastGrowthAt > MANUAL_IDLE_MS) break
            }

            // 3) Bring the chart back to the live edge and VERIFY it (newest chart candle == live price), dragging again
            //    until it is. The chart stops at the live edge by itself, so overshooting is harmless.
            var liveCandles: List<Candle> = emptyList()
            if (auto) {
                var atLive = false
                for (attempt in 0 until MAX_LIVE_RETURN_TRIES) {
                    com.jarvis.assistant.accessibility.JarvisAccessibilityService.panChart(axisLeft * 0.75f, axisLeft * 0.10f, screenH * 0.33f)
                    delay(PAN_SETTLE_MS)
                    val fr = readHistoryFrame(textReader, parser, config.candleMs, rightEdgeOnly = true) ?: continue
                    val price = fr.reading.price ?: continue
                    fr.reading.axisLeftX?.let { axisLeft = it.toFloat() }
                    if (fr.detection.candles.isNotEmpty() &&
                        com.jarvis.assistant.quotex.ocr.ChartCandleDetector.agreesWith(fr.detection, price, price * HISTORY_PRICE_TOLERANCE)
                    ) {
                        // Candles that closed while JARVIS was scrolling (no live sampling then): they are on the live chart now.
                        liveCandles = fr.detection.closed
                        atLive = true
                        break
                    }
                }
                if (!atLive) coordinator.setMessage("History: could not confirm the chart is back at the live edge. Tap the arrow button on the chart once.")
            }

            // 4) Save everything that closed (the newest candle was still forming).
            val closed = stitcher.candles.dropLast(1)
            val added = coordinator.importHistory(closed + liveCandles)
            coordinator.setMessage(
                when {
                    added < 0 -> "History: ${closed.size} candles read, but the asset name is not known yet - set it under 'Asset (manual)' and run this again."
                    else -> "History loaded: ${closed.size} candles read from the chart, $added new saved. " +
                        (if (auto) "The chart was dragged back to the live edge." else "Scroll the chart back to the latest candle.")
                }
            )
        } finally {
            com.jarvis.assistant.quotex.overlay.QuotexOverlayService.show(this)
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
        private const val ACTION_BACKFILL = "com.jarvis.assistant.quotex.BACKFILL"
        private const val TF_HITS_NEEDED = 3
        private const val FAST_SAMPLE_MS = 700L
        private val STANDARD_TIMEFRAMES = listOf(5, 10, 15, 30, 60, 120, 180, 300, 600, 900, 1800, 3600)
        private const val AUTO_BACKFILL_COOLDOWN_MS = 3 * 60_000L
        private const val SEED_WAIT_MS = 40_000L
        private const val MAX_BACKFILL_MS = 240_000L
        private const val MAX_STALLS = 6
        private const val MAX_LIVE_RETURN_TRIES = 15
        private const val LIVE_DRAG_MISMATCH_FRAMES = 8
        private const val LIVE_DRAG_COOLDOWN_MS = 20_000L
        private const val STALL_RETRY_MS = 1_500L
        private const val MAX_PANS = 80
        private const val PAN_SETTLE_MS = 1_000L
        private const val MANUAL_POLL_MS = 1_200L
        private const val MANUAL_IDLE_MS = 30_000L
        private const val HISTORY_MIN_CONFIDENCE = 0.5
        private const val HISTORY_PRICE_TOLERANCE = 0.0003
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

        /** Asks the running monitor to load older candles from the chart (see runBackfill). */
        fun backfill(context: Context) {
            try {
                context.startService(Intent(context, QuotexMonitorService::class.java).setAction(ACTION_BACKFILL))
            } catch (e: Exception) {
                // Not running.
            }
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
