package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.wingo.ocr.OcrLine
import kotlin.math.abs
import kotlin.math.round

data class AxisLabel(val value: Double, val y: Float)

data class QuotexReading(
    val price: Double?,
    val asset: String?,
    val note: String,
    val axisLabels: Int
)

/**
 * Finds the live price among the labels of a chart's price axis. Grid labels are evenly spaced; the live
 * price label is the single one that is NOT on that grid. It is then double-checked against the vertical
 * position it should have. If anything is ambiguous the answer is null - it never guesses.
 */
object GridPriceFinder {
    fun find(labels: List<AxisLabel>, heightTolerance: Float): Double? {
        val sorted = labels.distinctBy { it.value }.sortedBy { it.value }
        if (sorted.size < 5) return null
        val diffs = sorted.zipWithNext { a, b -> b.value - a.value }.sorted()
        val spacing = diffs[diffs.size / 2]
        if (spacing <= 0.0) return null
        val tolerance = spacing * 0.12

        var bestAnchor = sorted[0]
        var bestFit = -1
        for (a in sorted) {
            var fit = 0
            for (b in sorted) {
                val k = (b.value - a.value) / spacing
                if (abs(k - round(k)) * spacing <= tolerance) fit++
            }
            if (fit > bestFit) {
                bestFit = fit
                bestAnchor = a
            }
        }
        val off = sorted.filter {
            val k = (it.value - bestAnchor.value) / spacing
            abs(k - round(k)) * spacing > tolerance
        }
        if (off.size != 1) return null
        val candidate = off[0]
        val grid = sorted.filter { it.value != candidate.value }
        if (grid.size < 4) return null
        val low = grid.first()
        val high = grid.last()
        if (high.value == low.value) return null
        val slope = (high.y - low.y) / (high.value - low.value).toFloat()
        val expectedY = low.y + (candidate.value - low.value).toFloat() * slope
        if (abs(expectedY - candidate.y) > heightTolerance) return null
        return candidate.value
    }
}

/** Reads the asset name (top of the chart area) and the live price (right-hand axis) from OCR words. */
class QuotexScreenParser {
    private val priceRegex = Regex("^\\d{1,6}[.,]\\d{2,6}$")
    private val pairRegex = Regex("([A-Za-z]{3})\\s*/\\s*([A-Za-z]{3})")

    fun parse(lines: List<OcrLine>, width: Int, height: Int): QuotexReading {
        if (width <= 0 || height <= 0 || lines.isEmpty()) return QuotexReading(null, null, "No text on screen", 0)

        val topText = lines.filter { it.centerY < height * 0.2f }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .joinToString(" ") { it.text }
        val pair = pairRegex.find(topText)
        val asset = pair?.let {
            val base = it.groupValues[1].uppercase() + it.groupValues[2].uppercase()
            if (topText.contains("OTC", ignoreCase = true)) base + "_OTC" else base
        }

        val labels = ArrayList<AxisLabel>()
        val heights = ArrayList<Int>()
        for (line in lines) {
            val centerX = (line.left + line.right) / 2f
            if (centerX < width * 0.72f) continue
            val text = line.text.trim()
            if (!priceRegex.matches(text)) continue
            val value = text.replace(',', '.').toDoubleOrNull() ?: continue
            labels.add(AxisLabel(value, line.centerY))
            heights.add(line.height.coerceAtLeast(1))
        }
        if (labels.size < 5) {
            return QuotexReading(null, asset, "Only ${labels.size} price-axis labels readable", labels.size)
        }
        val medianHeight = heights.sorted()[heights.size / 2]
        val price = GridPriceFinder.find(labels, medianHeight * 1.5f)
        val note = if (price == null) "Live price label not identified" else "OK"
        return QuotexReading(price, asset, note, labels.size)
    }
}
