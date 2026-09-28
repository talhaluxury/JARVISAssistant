package com.jarvis.assistant.wingo.ocr

import kotlin.math.abs

/** Cheap change detection so OCR only runs when the history region actually changed. */
object FrameDiff {
    /** True when the mean absolute luminance difference exceeds [threshold] (0-255 scale). */
    fun differs(a: IntArray?, b: IntArray, threshold: Double): Boolean {
        if (a == null || a.size != b.size) return true
        if (b.isEmpty()) return false
        var total = 0L
        for (i in b.indices) total += abs(a[i] - b[i])
        return total.toDouble() / b.size > threshold
    }
}
