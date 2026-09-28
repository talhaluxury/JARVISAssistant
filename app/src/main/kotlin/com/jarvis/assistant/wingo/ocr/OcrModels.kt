package com.jarvis.assistant.wingo.ocr

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.RoundResult

/** One recognised word/line with its position (pixels, relative to the image that was read). */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = bottom - top
}

/** What the parser managed to read from one visual row of the history table. Nothing here is trusted yet. */
data class ParsedRow(
    val period: String?,
    val number: Int?,
    val bigSmall: BigSmall?,
    val color: String?,
    val confidence: Float,
    val rawText: String,
    val top: Int,
    val bottom: Int,
    val ambiguous: Boolean = false
)

enum class RowStatus { TRUSTED, UNCERTAIN, REJECTED }

data class RowVerdict(
    val row: ParsedRow,
    val status: RowStatus,
    val reason: String?,
    val result: RoundResult?
)
