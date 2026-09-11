package com.jarvis.assistant.remote

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Visible screen-sharing foreground service. It requires the Android system's
 * MediaProjection consent and sends frames only to the paired relay controller.
 */
class RemoteScreenService : Service() {
    private var reader: ImageReader? = null
    private var projection: android.media.projection.MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val busy = AtomicBoolean(false)
    private val lastProcessedAt = AtomicLong(0)
    private val minFrameIntervalMs = 350L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(4901, NotificationCompat.Builder(this, "jarvis_remote")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS remote control active")
            .setContentText("Screen sharing is active. Stop it from JARVIS Remote.")
            .setOngoing(true).build())
        bgThread = HandlerThread("JarvisRemoteCapture").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        RemoteSession.start()
        RemoteRelayClient.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (projection == null && intent != null) {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                } ?: return START_NOT_STICKY
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = pm.getMediaProjection(resultCode, data)
                projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() { teardownCapture() }
                }, bgHandler)
                startCapture()
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ ir ->
            val image = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (busy.get() || now - lastProcessedAt.get() < minFrameIntervalMs) {
                image.close(); return@setOnImageAvailableListener
            }
            busy.set(true)
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w
                val bitmap = android.graphics.Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buf)
                val cropped = if (bitmap.width != w)
                    android.graphics.Bitmap.createBitmap(bitmap, 0, 0, w, h) else bitmap
                val out = ByteArrayOutputStream()
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
                latestJpeg.set(out.toByteArray())
                if (cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
                lastProcessedAt.set(now)
                if (RemoteRelayClient.controllerConnected) {
                    RemoteRelayClient.sendFrame(latestJpeg.get() ?: ByteArray(0))
                }
            } catch (_: Exception) {
            } finally {
                image.close(); busy.set(false)
            }
        }, bgHandler)
        try {
            display = projection!!.createVirtualDisplay(
                "JARVISRemote", w, h, metrics.densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, bgHandler
            )
        } catch (_: Exception) { teardownCapture() }
    }

    private fun teardownCapture() {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        display = null; reader = null; latestJpeg.set(null)
    }

    override fun onDestroy() {
        RemoteSession.stop()
        teardownCapture()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { bgThread?.quitSafely() } catch (_: Exception) {}
        bgThread = null; bgHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("jarvis_remote", "JARVIS Remote", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        val latestJpeg = AtomicReference<ByteArray?>(null)
    }
}
