package com.jarvis.assistant.wingo.ocr

import com.jarvis.assistant.wingo.domain.BigSmall
import kotlin.math.abs

/**
 * Turns raw OCR words into candidate history rows. This is only a reader: it does not decide what is
 * true. Every row it returns still has to pass [ResultValidator] before it can be used.
 */
class WinGoOCRParser {

    private val periodRegex = Regex("(?<!\\d)\\d{10,20}(?!\\d)")
    private val labelRegex = Regex("\\b(big|small)\\b", RegexOption.IGNORE_CASE)
    private val colorRegex = Regex("\\b(red|green|violet|purple)\\b", RegexOption.IGNORE_CASE)

    fun parse(lines: List<OcrLine>): List<ParsedRow> {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()

        val heights = usable.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
        val tolerance = heights[heights.size / 2] * 0.6f

        val sorted = usable.sortedBy { it.centerY }
        val rows = ArrayList<MutableList<OcrLine>>()
        var rowCenter = 0f
        for (line in sorted) {
            if (rows.isEmpty() || abs(line.centerY - rowCenter) > tolerance) {
                rows.add(mutableListOf(line))
                rowCenter = line.centerY
            } else {
                val current = rows[rows.size - 1]
                current.add(line)
                rowCenter = current.map { it.centerY }.average().toFloat()
            }
        }
        return rows.mapNotNull { parseRow(it) }
    }

    private fun parseRow(row: List<OcrLine>): ParsedRow? {
        val ordered = row.sortedBy { it.left }
        val text = ordered.joinToString(" ") { it.text.trim() }
        val periodMatch = periodRegex.find(text) ?: return null
        val period = periodMatch.value

        val remainder = text.removeRange(periodMatch.range)
        var ambiguous = false

        val singleDigits = remainder.split(Regex("\\s+")).filter { it.length == 1 && it[0] in '0'..'9' }
        val number: Int? = when (singleDigits.size) {
            1 -> singleDigits[0].toInt()
            0 -> null
            else -> {
                ambiguous = true
                null
            }
        }

        val labels = labelRegex.findAll(remainder).map { it.value.lowercase() }.toSet()
        val bigSmall: BigSmall? = when (labels.size) {
            1 -> BigSmall.parse(labels.first())
            0 -> null
            else -> {
                ambiguous = true
                null
            }
        }

        val colors = colorRegex.findAll(remainder).map { it.value.uppercase() }.toList()
        val color = if (colors.isEmpty()) null else colors.joinToString("+")

        val confidence = ordered.minOf { it.confidence }
        return ParsedRow(
            period = period, number = number, bigSmall = bigSmall, color = color,
            confidence = confidence, rawText = text,
            top = ordered.minOf { it.top }, bottom = ordered.maxOf { it.bottom },
            ambiguous = ambiguous
        )
    }
}
