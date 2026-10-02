package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.wingo.ocr.OcrLine
import kotlin.math.abs
import kotlin.math.round

data class AxisLabel(val value: Double, val y: Float, val confidence: Float = 1f)

/**
 * One OCR reading. [confidence] (0..1) is how much the reader itself trusted the numbers the price was taken
 * from; it is null when the engine reports no usable confidence (never a made-up value).
 */
data class QuotexReading(
    val price: Double?,
    val asset: String?,
    val note: String,
    val axisLabels: Int,
    val confidence: Double? = null,
    /** Axis labels that sit on the evenly spaced grid (the live-price chip excluded): used to calibrate pixel -> price. */
    val gridLabels: List<AxisLabel> = emptyList(),
    /** Left edge (px, in the cropped image) of the price axis text: candles are only searched to the left of it. */
    val axisLeftX: Int? = null
)

/**
 * Finds the live price among the labels of a chart's price axis. Grid labels are evenly spaced; the live
 * price label is the single one that is NOT on that grid. It is then double-checked against the vertical
 * position it should have. If anything is ambiguous the answer is null - it never guesses.
 */
object GridPriceFinder {
    fun find(labels: List<AxisLabel>, heightTolerance: Float, minLabels: Int = 5, minGrid: Int = 4): Double? {
        val sorted = labels.distinctBy { it.value }.sortedBy { it.value }
        if (sorted.size < minLabels) return null
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
        if (grid.size < minGrid) return null
        val low = grid.first()
        val high = grid.last()
        if (high.value == low.value) return null
        val slope = (high.y - low.y) / (high.value - low.value).toFloat()
        val expectedY = low.y + (candidate.value - low.value).toFloat() * slope
        if (abs(expectedY - candidate.y) > heightTolerance) return null
        return candidate.value
    }

    /**
     * For charts that only show a few axis numbers (phones): removes each label in turn and asks whether the
     * rest form an evenly spaced grid. The live price is the one label whose removal leaves a perfect grid
     * AND which sits off that grid at the right height. Zero or several matches -> null (never a guess).
     */
    fun findLeaveOneOut(labels: List<AxisLabel>, heightTolerance: Float, minGrid: Int = 3): Double? {
        val sorted = labels.distinctBy { it.value }.sortedBy { it.value }
        if (sorted.size < minGrid + 1) return null
        var found: AxisLabel? = null
        for (candidate in sorted) {
            val grid = sorted.filter { it !== candidate }
            val diffs = grid.zipWithNext { a, b -> b.value - a.value }
            if (diffs.isEmpty()) continue
            val step = diffs.average()
            if (step <= 0.0) continue
            if (diffs.any { abs(it - step) > step * 0.12 }) continue
            val low = grid.first()
            val high = grid.last()
            val k = (candidate.value - low.value) / step
            if (abs(k - round(k)) * step <= step * 0.12) continue // sits on the grid: cannot be told apart
            if (high.value == low.value) continue
            val slope = (high.y - low.y) / (high.value - low.value).toFloat()
            val expectedY = low.y + (candidate.value - low.value).toFloat() * slope
            if (abs(expectedY - candidate.y) > heightTolerance) continue
            if (found != null) return null // ambiguous
            found = candidate
        }
        return found?.value
    }
}

/** Reads the asset name (top of the chart area) and the live price (right-hand axis) from OCR words. */
class QuotexScreenParser {
    private val priceRegex = Regex("^\\d{1,6}[.,]\\d{2,6}$")
    private val pairRegex = Regex("\\b([A-Za-z]{3})\\s*/\\s*([A-Za-z]{3})\\b")
    private val joinedPairRegex = Regex("\\b([A-Za-z]{3})([A-Za-z]{3})\\b")
    private val currencyCodes = setOf(
        "EUR", "USD", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD", "HKD", "TRY", "ZAR", "MXN", "NOK", "SEK", "PLN",
        "INR", "BRL", "CNH", "XAU", "XAG", "BTC", "ETH", "LTC", "XRP"
    )

    fun parse(lines: List<OcrLine>, width: Int, height: Int): QuotexReading {
        if (width <= 0 || height <= 0 || lines.isEmpty()) {
            return QuotexReading(
                null, null,
                "No text read from the screen. Open the Quotex chart and keep it visible; if it is open, Quotex may be blocking screen capture.",
                0
            )
        }

        // The asset name can be at the top (web / tablet) or in the bottom trade panel (phone app): search it all.
        val textLines = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val allText = textLines.joinToString(" ") { it.text }
        // JARVIS's own app screen is on display (not the broker's chart): reading it would invent assets and prices.
        val lettersOnly = allText.uppercase().filter { it.isLetter() }
        if ("AGENTSTATUS" in lettersOnly || "QUOTEXANALYZER" in lettersOnly) {
            return QuotexReading(
                null, null,
                "JARVIS's own screen is showing, not the Quotex chart. Open Quotex and use the floating overlay instead of this screen.",
                0
            )
        }
        val asset = findAsset(textLines, allText)

        val labels = ArrayList<AxisLabel>()
        val heights = ArrayList<Int>()
        for (line in lines) {
            val centerX = (line.left + line.right) / 2f
            if (centerX < width * 0.65f) continue
            val text = line.text.trim().filter { it.isDigit() || it == '.' || it == ',' }
            if (!priceRegex.matches(text)) continue
            val value = text.replace(',', '.').toDoubleOrNull() ?: continue
            labels.add(AxisLabel(value, line.centerY, line.confidence))
            heights.add(line.height.coerceAtLeast(1))
        }
        // Other numbers on the right edge (e.g. the payout amount) are not prices: drop anything far from the median.
        if (labels.size >= 3) {
            val median = labels.map { it.value }.sorted()[labels.size / 2]
            val keep = labels.indices.filter { abs(labels[it].value - median) <= abs(median) * 0.10 }
            if (keep.size < labels.size) {
                val l2 = keep.map { labels[it] }
                val h2 = keep.map { heights[it] }
                labels.clear(); labels.addAll(l2)
                heights.clear(); heights.addAll(h2)
            }
        }
        val assetNote = if (asset == null) "asset name not found" else "asset $asset"
        if (labels.size < 4) {
            return QuotexReading(
                null, asset,
                "Only ${labels.size} price-axis numbers readable (need 4+), $assetNote. Show the chart's right-hand price scale, or adjust the screen region in settings.",
                labels.size
            )
        }
        val medianHeight = heights.sorted()[heights.size / 2]
        val tolerance = medianHeight * 2.5f // axis labels sit a little above their grid lines, the live chip is centred on its line
        var price = GridPriceFinder.find(labels, tolerance)
        var relaxed = false
        if (price == null) {
            price = GridPriceFinder.findLeaveOneOut(labels, tolerance)
            relaxed = price != null
        }
        val note = when {
            price == null -> "Read ${labels.size} axis numbers but could not tell which one is the live price, $assetNote."
            relaxed -> "OK (few axis numbers), $assetNote"
            else -> "OK, $assetNote"
        }
        val grid = if (price == null) emptyList() else labels.filter { it.value != price }
        val keptValues = labels.map { it.value }.toSet()
        val axisLeft = if (price == null) null else lines.filter { line ->
            (line.left + line.right) / 2f >= width * 0.65f &&
                line.text.trim().filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').toDoubleOrNull() in keptValues
        }.minOfOrNull { it.left }
        return QuotexReading(price, asset, note, labels.size, if (price == null) null else readingConfidence(labels, price), grid, axisLeft)
    }

    /**
     * Confidence of one reading = the weaker of (a) the live-price label itself and (b) the average of the grid
     * labels it was validated against. A reader that reports 0 for everything is treated as "unknown" (null),
     * so a missing score can never be mistaken for a measured one.
     */
    private fun readingConfidence(labels: List<AxisLabel>, price: Double): Double? {
        val priceLabel = labels.firstOrNull { it.value == price } ?: return null
        val grid = labels.filter { it !== priceLabel }
        if (priceLabel.confidence <= 0f || grid.isEmpty() || grid.all { it.confidence <= 0f }) return null
        val gridAvg = grid.filter { it.confidence > 0f }.map { it.confidence.toDouble() }.average()
        return minOf(priceLabel.confidence.toDouble(), gridAvg).coerceIn(0.0, 1.0)
    }

    private val notAssetWords = setOf(
        "ASSET", "SIGNAL", "QUOTEX", "JARVIS", "DEPOSIT", "TRADE", "TRADES", "BUY", "SELL", "TIMER", "PAYOUT", "INVESTMENT",
        "SWITCH", "PENDING", "LIVE", "DEMO", "WAIT", "CHAT", "WHY", "ACCURACY", "BACKTEST", "ANALYSIS", "AGENT", "REGIME",
        "TREND", "BALANCE", "BONUS", "UTC"
    )

    private fun otcNear(anchor: OcrLine?, lines: List<OcrLine>): Boolean {
        if (anchor == null) return false
        val tolerance = maxOf(anchor.height, 24) * 1.5f
        return lines.any { it.text.contains("OTC", ignoreCase = true) && abs(it.centerY - anchor.centerY) <= tolerance }
    }

    private fun findAsset(lines: List<OcrLine>, text: String): String? {
        // "OTC" only counts when it sits on the same row as the asset name - not anywhere on the screen.
        fun tagged(a: String, b: String): String {
            val anchor = lines.firstOrNull { it.text.uppercase().contains(a) }
            return if (otcNear(anchor, lines)) a + b + "_OTC" else a + b
        }

        val slashPairs = pairRegex.findAll(text).toList()
        val known = slashPairs.firstOrNull {
            it.groupValues[1].uppercase() in currencyCodes && it.groupValues[2].uppercase() in currencyCodes
        }
        (known ?: slashPairs.firstOrNull())?.let { return tagged(it.groupValues[1].uppercase(), it.groupValues[2].uppercase()) }

        for (m in joinedPairRegex.findAll(text)) {
            val a = m.groupValues[1].uppercase()
            val b = m.groupValues[2].uppercase()
            if (a in currencyCodes && b in currencyCodes && a != b) return tagged(a, b)
        }
        // Non-pair assets (stocks, commodities): the words just left of the "(OTC)" tag on the same row.
        val otcLine = lines.firstOrNull { it.text.contains("OTC", ignoreCase = true) } ?: return null
        val inline = otcLine.text.substringBefore("(", "").filter { it.isLetter() }
        if (inline.length >= 3 && inline.uppercase() !in notAssetWords) return inline.uppercase() + "_OTC"
        val rowTolerance = maxOf(otcLine.height, 20)
        val words = lines
            .filter { abs(it.centerY - otcLine.centerY) <= rowTolerance && it.right <= otcLine.left + 4 }
            .sortedBy { it.left }
            .map { it.text.trim('(', ')', ' ') }
            .filter { w ->
                w.length >= 3 && w.all { c -> c.isLetter() } && !w.equals("OTC", ignoreCase = true) && w.uppercase() !in notAssetWords
            }
        val name = words.takeLast(2).joinToString("") { it.uppercase() }
        return if (name.isNotEmpty()) name + "_OTC" else null
    }
}
