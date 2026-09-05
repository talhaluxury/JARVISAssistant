package com.jarvis.assistant.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.jarvis.assistant.hud.DeviceTelemetry
import com.jarvis.assistant.hud.DeviceTelemetryRepository
import com.jarvis.assistant.hud.HudMode
import com.jarvis.assistant.hud.JarvisHudState
import com.jarvis.assistant.hud.WallpaperEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Real Android Live WallpaperService. Rendering is deliberately lightweight Canvas drawing. */
class LiveWallpaperService : WallpaperService() {
    override fun onCreate() {
        super.onCreate()
        WallpaperEventBus.initialize(this)
    }

    override fun onCreateEngine(): Engine = JarvisEngine()

    private inner class JarvisEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val telemetryRepo = DeviceTelemetryRepository(applicationContext)
        private val settingsRepo = com.jarvis.assistant.hud.HudSettingsRepository(applicationContext)
        private var stateJob: Job? = null
        private var telemetryJob: Job? = null
        private var visible = false
        private var phase = 0f
        private var state = WallpaperEventBus.state.value
        private var telemetry = telemetryRepo.telemetry.value
        private var settings = settingsRepo.read()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE }
        private val path = Path()
        private val rect = RectF()
        private val cyan = Color.rgb(56, 220, 255)
        private val cyanSoft = Color.rgb(22, 125, 170)
        private val bg = Color.rgb(2, 7, 13)
        private val drawRunnable = Runnable { drawFrame() }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                stateJob = scope.launch {
                    WallpaperEventBus.state.collectLatest { state = it }
                }
                telemetryJob = scope.launch {
                    telemetryRepo.telemetry.collectLatest { telemetry = it }
                }
                telemetryRepo.start(scope, if (state.mode == HudMode.STANDBY) 5000L else 2000L)
                handler.post(drawRunnable)
            } else {
                stateJob?.cancel(); stateJob = null
                telemetryJob?.cancel(); telemetryJob = null
                telemetryRepo.stop()
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (visible) handler.post(drawRunnable)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            stateJob?.cancel(); telemetryJob?.cancel()
            telemetryRepo.stop()
            handler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            scope.cancel()
            telemetryRepo.stop()
            super.onDestroy()
        }

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) render(canvas)
            } finally {
                canvas?.let { runCatching { surfaceHolder.unlockCanvasAndPost(it) } }
            }
            phase += phaseStep() * settings.animationSpeed
            if (visible) handler.postDelayed(drawRunnable, frameDelay())
        }

        private fun render(c: Canvas) {
            settings = settingsRepo.read()
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            if (w <= 0f || h <= 0f) return
            c.drawColor(bg)
            drawGrid(c, w, h)
            val cx = w / 2f
            val cy = h * 0.43f
            val r = min(w, h) * if (state.mode == HudMode.MINIMAL) .16f else .19f
            drawCore(c, cx, cy, r)
            if (state.mode != HudMode.MINIMAL) {
                drawTelemetryPanels(c, w, h, cx, cy, r, settings)
                drawBottomStatus(c, w, h)
            }
            drawScanline(c, w, h)
            drawHeader(c, w)
            if (state.mode != HudMode.MINIMAL) drawDayRing(c, w, h)
            val dimAlpha = ((1f - settings.brightness.coerceIn(.1f, 1f)) * 170f).toInt()
            if (dimAlpha > 0) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(dimAlpha, 0, 0, 0)
                c.drawRect(0f, 0f, w, h, p)
            }
        }

        private fun drawGrid(c: Canvas, w: Float, h: Float) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = Color.argb(if (state.mode == HudMode.STANDBY) 14 else 25, Color.red(cyanSoft), Color.green(cyanSoft), Color.blue(cyanSoft))
            val step = min(w, h) / 12f
            var x = 0f
            while (x <= w) { c.drawLine(x, 0f, x, h, p); x += step }
            var y = 0f
            while (y <= h) { c.drawLine(0f, y, w, y, p); y += step }
        }

        private fun drawCore(c: Canvas, cx: Float, cy: Float, r: Float) {
            val active = state.state != JarvisHudState.IDLE && state.state != JarvisHudState.COMPLETED
            val speed = when (state.state) {
                JarvisHudState.LISTENING -> 2.8f
                JarvisHudState.THINKING, JarvisHudState.PLANNING -> 2.2f
                JarvisHudState.EXECUTING, JarvisHudState.VERIFYING -> 3.4f
                JarvisHudState.ERROR -> 0.8f
                JarvisHudState.COMPLETED -> 1.2f
                else -> 0.7f
            }
            val intensity = settings.animationIntensity
            val pulse = 1f + sin(phase * 2.0) * (if (active) .055f else .025f) * intensity
            val rr = r * pulse
            val color = when (state.state) {
                JarvisHudState.ERROR -> Color.rgb(255, 90, 90)
                JarvisHudState.PERMISSION_REQUIRED -> Color.rgb(255, 190, 80)
                JarvisHudState.COMPLETED -> Color.rgb(100, 255, 190)
                else -> cyan
            }

            p.style = Paint.Style.FILL
            p.shader = android.graphics.RadialGradient(
                cx, cy, rr * 2.5f,
                intArrayOf(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, rr * 2.5f, p)
            p.shader = null

            ringPaint.color = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
            ringPaint.strokeWidth = 2f
            for (i in 0..4) {
                val radius = rr * (0.72f + i * .22f)
                ringPaint.strokeWidth = if (i == 2) 3f else 1.2f
                rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                c.save()
                c.rotate(phase * speed * (if (i % 2 == 0) 1f else -1f), cx, cy)
                c.drawArc(rect, i * 24f, 275f, false, ringPaint)
                c.restore()
            }

            // Segmented progress / execution ring.
            if (state.progress > 0f) {
                ringPaint.color = color
                ringPaint.strokeWidth = 5f
                rect.set(cx - rr * 1.36f, cy - rr * 1.36f, cx + rr * 1.36f, cy + rr * 1.36f)
                c.drawArc(rect, -90f, state.progress * 360f, false, ringPaint)
            }

            // Reactor core.
            p.style = Paint.Style.FILL
            p.color = Color.argb(235, Color.red(color), Color.green(color), Color.blue(color))
            c.drawCircle(cx, cy, rr * .28f, p)
            p.color = Color.WHITE
            c.drawCircle(cx, cy, rr * .10f, p)

            drawTickRing(c, cx, cy, rr * 1.62f, color)
            drawWaveform(c, cx, cy, rr, color)
            drawCoreLabels(c, cx, cy, rr, color)
        }

        /** Radial tick marks around the outer edge of the core — the "technical dial" look. */
        private fun drawTickRing(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, count: Int = 72) {
            ringPaint.color = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
            for (i in 0 until count) {
                val major = i % 6 == 0
                ringPaint.strokeWidth = if (major) 2f else 1f
                val a = Math.toRadians((i * 360.0 / count) + phase * 6.0)
                val len = if (major) r * .06f else r * .03f
                val cosA = cos(a).toFloat()
                val sinA = sin(a).toFloat()
                c.drawLine(cx + cosA * r, cy + sinA * r, cx + cosA * (r - len), cy + sinA * (r - len), ringPaint)
            }
        }

        private fun drawWaveform(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
            path.reset()
            val points = 64
            for (i in 0 until points) {
                val x = cx - r * 1.45f + i * (r * 2.9f / (points - 1))
                val amp = if (state.state == JarvisHudState.LISTENING) .18f else .045f
                val y = cy + sin(phase * 3f + i * .65f) * r * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            ringPaint.color = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
            ringPaint.strokeWidth = 1.4f
            c.drawPath(path, ringPaint)
        }

        private fun drawCoreLabels(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = maxOf(10f, r * .15f)
            textPaint.color = color
            c.drawText("JARVIS CORE", cx, cy - r * 1.58f, textPaint)
            textPaint.textSize = maxOf(8f, r * .10f)
            textPaint.color = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
            c.drawText(state.state.name.replace('_', ' '), cx, cy + r * 1.62f, textPaint)
        }

        private fun drawTelemetryPanels(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, r: Float, settings: com.jarvis.assistant.hud.HudSettings) {
            val leftX = w * .055f
            val rightX = w * .945f
            val top = h * .17f
            val gap = h * .115f

            // Ring-gauge cluster, top-left — mirrors the classic circular-meter HUD skin look.
            val gaugeR = min(w, h) * .052f
            val gaugeY = h * .12f
            var gx = leftX + gaugeR
            if (settings.showBattery) {
                drawRingGauge(c, gx, gaugeY, gaugeR, "BATTERY", telemetry.batteryPercent?.let { "$it%" } ?: "—",
                    telemetry.batteryPercent?.let { it / 100f }, state.focus == "BATTERY")
                gx += gaugeR * 2.6f
            }
            if (settings.showRam) {
                val pct = if (telemetry.ramUsedGb != null && telemetry.ramTotalGb != null && telemetry.ramTotalGb!! > 0f)
                    telemetry.ramUsedGb!! / telemetry.ramTotalGb!! else null
                drawRingGauge(c, gx, gaugeY, gaugeR, "RAM", pct?.let { "${(it * 100).toInt()}%" } ?: "—", pct, state.focus == "SYSTEM")
                gx += gaugeR * 2.6f
            }
            if (settings.showStorage) {
                val pct = if (telemetry.storageUsedGb != null && telemetry.storageTotalGb != null && telemetry.storageTotalGb!! > 0f)
                    telemetry.storageUsedGb!! / telemetry.storageTotalGb!! else null
                drawRingGauge(c, gx, gaugeY, gaugeR, "DISK", pct?.let { "${(it * 100).toInt()}%" } ?: "—", pct, state.focus == "SYSTEM")
            }

            if (settings.showNetwork) {
                drawPanelRight(c, rightX, top, "NETWORK", status(telemetry.networkConnected), state.focus == "NETWORK")
                drawPanelRight(c, rightX, top + gap, "WIFI", status(telemetry.wifiConnected), state.focus == "NETWORK")
                drawPanelRight(c, rightX, top + gap * 2, "BT", status(telemetry.bluetoothEnabled), state.focus == "NETWORK")
            }

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 10f
            textPaint.color = Color.argb(170, 150, 220, 235)
            if (settings.showDevice) {
                c.drawText(telemetry.deviceModel.take(24), cx, h * .76f, textPaint)
                c.drawText("ANDROID ${telemetry.androidVersion}", cx, h * .785f, textPaint)
                telemetry.foregroundApp?.let { c.drawText("APP: ${it.substringAfterLast('.').take(22).uppercase()}", cx, h * .81f, textPaint) }
            }
            if (settings.showNotifications) telemetry.notificationCount?.let {
                c.drawText("NOTIFICATIONS ${it.toString().padStart(2, '0')}", cx, h * .835f, textPaint)
            }
        }

        /** A circular donut meter with a value in the center and a label underneath — the
         * ring-gauge look from classic Rainmeter-style HUD skins (CPU/RAM/battery dials). */
        private fun drawRingGauge(c: Canvas, cx: Float, cy: Float, r: Float, label: String, valueText: String, percent: Float?, focused: Boolean) {
            val color = if (focused) cyan else Color.rgb(90, 190, 215)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            ringPaint.strokeWidth = r * .16f
            ringPaint.color = Color.argb(55, Color.red(cyanSoft), Color.green(cyanSoft), Color.blue(cyanSoft))
            c.drawArc(rect, 0f, 360f, false, ringPaint)
            if (percent != null) {
                ringPaint.color = Color.argb(230, Color.red(color), Color.green(color), Color.blue(color))
                c.drawArc(rect, -90f, percent.coerceIn(0f, 1f) * 360f, false, ringPaint)
            }
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = r * .4f
            textPaint.color = color
            c.drawText(valueText, cx, cy + r * .15f, textPaint)
            textPaint.textSize = r * .24f
            textPaint.color = Color.argb(190, 150, 210, 225)
            c.drawText(label, cx, cy + r * 1.45f, textPaint)
        }

        private fun drawPanel(c: Canvas, x: Float, y: Float, label: String, value: String, focused: Boolean) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 9f
            textPaint.color = if (focused) cyan else Color.argb(155, 145, 200, 215)
            c.drawText(label, x, y, textPaint)
            textPaint.textSize = 11f
            c.drawText(value.take(22), x, y + 15f, textPaint)
            c.drawLine(x, y + 20f, x + 72f, y + 20f, ringPaint.apply { color = Color.argb(100, 50, 160, 190); strokeWidth = 1f })
        }

        private fun drawPanelRight(c: Canvas, x: Float, y: Float, label: String, value: String, focused: Boolean) {
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = 9f
            textPaint.color = if (focused) cyan else Color.argb(155, 145, 200, 215)
            c.drawText(label, x, y, textPaint)
            textPaint.textSize = 11f
            c.drawText(value, x, y + 15f, textPaint)
        }

        private fun drawBottomStatus(c: Canvas, w: Float, h: Float) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 9f
            textPaint.color = cyan
            c.drawText(state.commandStatus.take(44).uppercase(), w / 2f, h * .92f, textPaint)
            textPaint.textSize = 8f
            textPaint.color = Color.argb(120, 130, 200, 220)
            c.drawText("JARVIS PERSONAL AI CONTROL SYSTEM", w / 2f, h * .95f, textPaint)
        }

        private fun drawScanline(c: Canvas, w: Float, h: Float) {
            p.style = Paint.Style.FILL
            p.color = Color.argb(if (state.mode == HudMode.STANDBY) 7 else 14, 70, 210, 240)
            val y = ((phase * 18f) % (h + 40f)) - 20f
            c.drawRect(0f, y, w, y + 1.5f, p)
        }

        private fun drawHeader(c: Canvas, w: Float) {
            val settings = settingsRepo.read()
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val date = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 10f
            textPaint.color = Color.argb(190, 120, 215, 235)
            c.drawText("JARVIS // ${state.state.name}", 18f, 28f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            if (settings.showClock) {
                c.drawText(now, w - 18f, 28f, textPaint)
                textPaint.textSize = 8f
                c.drawText(date.uppercase(), w - 18f, 42f, textPaint)
            }
        }

        /** Circular "day" dial in the corner, in the spirit of the reference HUD skin's date ring. */
        private fun drawDayRing(c: Canvas, w: Float, h: Float) {
            val r = min(w, h) * .045f
            val cx = w - r * 1.6f
            val cy = r * 2.4f
            val day = java.util.Calendar.getInstance()
            rect.set(cx - r, cy - r, cx + r, cy + r)
            ringPaint.strokeWidth = 2.2f
            ringPaint.color = Color.argb(200, Color.red(cyan), Color.green(cyan), Color.blue(cyan))
            c.drawArc(rect, -90f, (day.get(java.util.Calendar.DAY_OF_MONTH) / 31f) * 360f, false, ringPaint)
            ringPaint.color = Color.argb(45, Color.red(cyanSoft), Color.green(cyanSoft), Color.blue(cyanSoft))
            ringPaint.strokeWidth = 1.2f
            c.drawArc(rect, 0f, 360f, false, ringPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = r * .8f
            textPaint.color = cyan
            c.drawText(day.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0'), cx, cy + r * .3f, textPaint)
        }

        private fun phaseStep(): Float = when (state.mode) {
            HudMode.STANDBY -> .018f
            HudMode.MINIMAL -> .028f
            else -> when (state.state) {
                JarvisHudState.LISTENING -> .15f
                JarvisHudState.THINKING, JarvisHudState.PLANNING -> .12f
                JarvisHudState.EXECUTING, JarvisHudState.VERIFYING -> .18f
                else -> .045f
            }
        }

        private fun frameDelay(): Long = when (state.mode) {
            HudMode.STANDBY -> 120L
            HudMode.MINIMAL -> 70L
            else -> when (state.state) {
                JarvisHudState.IDLE -> 80L
                JarvisHudState.LISTENING, JarvisHudState.EXECUTING -> 33L
                else -> 50L
            }
        }

        private fun status(value: Boolean?): String = when (value) {
            true -> "CONNECTED"
            false -> "OFF"
            null -> "UNAVAILABLE"
        }

        private fun fmt(v: Float?): String = v?.let { "%.1f".format(it) } ?: "—"
    }
}
