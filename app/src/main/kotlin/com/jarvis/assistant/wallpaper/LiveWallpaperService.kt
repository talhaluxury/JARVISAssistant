package com.jarvis.assistant.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.jarvis.assistant.voice.JarvisGlobalState
import com.jarvis.assistant.voice.VoiceState
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Paints the JARVIS orb directly behind the home screen icons, as a normal Android live
 * wallpaper. This class is visual ONLY — it never opens the microphone itself. The actual
 * "always listening" pipeline lives in [com.jarvis.assistant.overlay.OverlayService]; this
 * wallpaper just watches [JarvisGlobalState] (which that service updates) and animates
 * accordingly, so the background visibly pulses and changes color while JARVIS is
 * listening/thinking/speaking — without this class needing RECORD_AUDIO at all.
 *
 * The system only renders this while it's actually the active wallpaper and on-screen
 * ([onVisibilityChanged]); it stops drawing (and stops collecting state) the instant the
 * screen is off or another wallpaper/app covers it, so it costs nothing while not visible.
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = JarvisEngine()

    private inner class JarvisEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var stateJob: Job? = null
        private var isVisible = false
        private var currentState = VoiceState.IDLE
        private var phase = 0f

        private val drawRunnable = Runnable { drawFrame() }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                stateJob = engineScope.launch {
                    JarvisGlobalState.state.collect { currentState = it }
                }
                handler.post(drawRunnable)
            } else {
                stateJob?.cancel()
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            stateJob?.cancel()
            handler.removeCallbacks(drawRunnable)
        }

        override fun onDestroy() {
            super.onDestroy()
            engineScope.cancel()
        }

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                canvas?.let(::render)
            } finally {
                canvas?.let { runCatching { surfaceHolder.unlockCanvasAndPost(it) } }
            }
            phase += speedFor(currentState)
            if (isVisible) handler.postDelayed(drawRunnable, FRAME_DELAY_MS)
        }

        private fun render(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            canvas.drawColor(BACKGROUND_COLOR)
            if (w <= 0f || h <= 0f) return

            val cx = w / 2f
            val cy = h * 0.42f
            val baseRadius = min(w, h) * 0.11f
            val pulse = (sin(phase.toDouble()) * 0.18 + 1.0).toFloat()
            val radius = baseRadius * pulse
            val color = colorFor(currentState)

            canvas.drawCircle(
                cx, cy, radius * 3.4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        cx, cy, radius * 3.4f,
                        intArrayOf(withAlpha(color, 90), withAlpha(color, 0)),
                        null, Shader.TileMode.CLAMP
                    )
                }
            )
            canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            canvas.drawCircle(
                cx, cy, radius * 1.7f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                    this.color = withAlpha(color, 160)
                }
            )
        }

        private fun speedFor(state: VoiceState): Float = when (state) {
            VoiceState.LISTENING -> 0.20f
            VoiceState.THINKING -> 0.14f
            VoiceState.SPEAKING -> 0.24f
            VoiceState.ERROR -> 0.06f
            VoiceState.IDLE -> 0.045f
        }

        private fun colorFor(state: VoiceState): Int = when (state) {
            VoiceState.LISTENING -> Color.parseColor("#FF6B6B")
            VoiceState.THINKING -> Color.parseColor("#7C6BFF")
            VoiceState.SPEAKING -> Color.parseColor("#34D399")
            VoiceState.ERROR -> Color.parseColor("#F87171")
            VoiceState.IDLE -> Color.parseColor("#38BDF8")
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    companion object {
        private const val FRAME_DELAY_MS = 32L // ~30fps, gentle on battery
        private val BACKGROUND_COLOR = Color.parseColor("#0A0E14")
    }
}
