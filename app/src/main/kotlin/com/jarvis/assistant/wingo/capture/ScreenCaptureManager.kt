package com.jarvis.assistant.wingo.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.jarvis.assistant.wingo.domain.NormalizedRegion

/**
 * Pull-based screen capture. Frames are only converted to a Bitmap when asked for, and the last frame is
 * cached because a VirtualDisplay produces no new frames while the screen is static. Capture only exists
 * while the user-approved MediaProjection is alive.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val projection: MediaProjection,
    private val onProjectionStopped: () -> Unit
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var cached: Bitmap? = null
    private var width = 0
    private var height = 0

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }
    }

    fun start() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds: Rect = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        width = bounds.width()
        height = bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi

        // Android 14+: the callback must be registered before the virtual display is created.
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = projection.createVirtualDisplay(
            "JARVISWinGo", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null
        )
    }

    /** Newest frame, or the previous one if the screen has not changed. Call from one thread only. */
    fun latestFrame(): Bitmap? {
        val imageReader = reader ?: return cached
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (image != null) {
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val exact = if (padded.width != width) Bitmap.createBitmap(padded, 0, 0, width, height) else padded
                if (exact !== padded) padded.recycle()
                cached?.recycle()
                cached = exact
            } finally {
                image.close()
            }
        }
        return cached
    }

    fun close() {
        try { display?.release() } catch (e: Exception) { }
        display = null
        try { reader?.close() } catch (e: Exception) { }
        reader = null
        try { projection.unregisterCallback(callback) } catch (e: Exception) { }
        try { projection.stop() } catch (e: Exception) { }
        cached?.recycle()
        cached = null
    }

    companion object {
        /** Crops [frame] to [region] (fractions of the frame). Returns a new bitmap. */
        fun crop(frame: Bitmap, region: NormalizedRegion): Bitmap {
            val x = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
            val y = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
            val w = ((region.right - region.left) * frame.width).toInt().coerceIn(1, frame.width - x)
            val h = ((region.bottom - region.top) * frame.height).toInt().coerceIn(1, frame.height - y)
            return Bitmap.createBitmap(frame, x, y, w, h)
        }

        /** 24x24 luminance signature used to decide whether OCR needs to run again. */
        fun signature(bitmap: Bitmap): IntArray {
            val size = 24
            val small = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val pixels = IntArray(size * size)
            small.getPixels(pixels, 0, size, 0, 0, size, size)
            if (small !== bitmap) small.recycle()
            return IntArray(pixels.size) {
                val c = pixels[it]
                (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
        }
    }
}
