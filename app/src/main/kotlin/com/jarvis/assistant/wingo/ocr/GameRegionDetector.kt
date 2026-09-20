package com.jarvis.assistant.wingo.ocr

import com.jarvis.assistant.wingo.domain.NormalizedRegion
import com.jarvis.assistant.wingo.domain.PeriodFormat

data class GameDetection(
    val visible: Boolean,
    val historyRegion: NormalizedRegion?,
    val dataRows: Int,
    val message: String
)

/**
 * Decides from a full-frame OCR pass whether the WinGo history table is on screen and where.
 * It only claims "visible" when several rows look like real results (period + number/label).
 */
class GameRegionDetector(
    private val minDataRows: Int = 3,
    private val periodLength: Int = PeriodFormat.DEFAULT_LENGTH
) {
    private val parser = WinGoOCRParser()

    fun detect(lines: List<OcrLine>, frameWidth: Int, frameHeight: Int): GameDetection {
        val notFound = GameDetection(false, null, 0, "WinGo screen not detected.")
        if (frameWidth <= 0 || frameHeight <= 0) return notFound
        val rows = parser.parse(lines).filter {
            val p = it.period
            p != null && PeriodFormat.isValid(p, periodLength) && (it.bigSmall != null || it.number != null)
        }
        if (rows.size < minDataRows) return notFound

        val top = rows.minOf { it.top }
        val bottom = rows.maxOf { it.bottom }
        val rowHeight = (bottom - top).toFloat() / rows.size
        val margin = rowHeight * 0.75f
        val topFraction = ((top - margin) / frameHeight).coerceIn(0f, 0.98f)
        val bottomFraction = ((bottom + margin) / frameHeight).coerceIn(topFraction + 0.02f, 1f)
        val region = NormalizedRegion(0f, topFraction, 1f, bottomFraction)
        return GameDetection(true, region, rows.size, "WinGo history detected (${rows.size} rows).")
    }
}
