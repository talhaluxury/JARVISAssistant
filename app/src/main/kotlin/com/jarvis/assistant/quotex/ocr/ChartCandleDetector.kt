package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Linear map between a screen row (pixels) and a price, fitted to the evenly spaced price-axis labels (section 31).
 * [fit] returns null unless the labels really do lie on one straight line - a bad calibration is never used.
 */
class PriceAxisCalibration private constructor(
    private val slope: Double,
    private val intercept: Double,
    val rSquared: Double
) {
    fun priceAt(y: Double): Double = slope * y + intercept

    companion object {
        fun fit(labels: List<AxisLabel>, minLabels: Int = 3, minRSquared: Double = 0.9995): PriceAxisCalibration? {
            val pts = labels.distinctBy { it.value }
            if (pts.size < minLabels) return null
            return fitAll(pts, minRSquared)
                ?: pts.indices.mapNotNull { skip -> fitAll(pts.filterIndexed { i, _ -> i != skip }.takeIf { it.size >= minLabels } ?: return@mapNotNull null, minRSquared) }
                    .maxByOrNull { it.rSquared }
        }

        private fun fitAll(pts: List<AxisLabel>, minRSquared: Double): PriceAxisCalibration? {
            val n = pts.size.toDouble()
            val mx = pts.sumOf { it.y.toDouble() } / n
            val my = pts.sumOf { it.value } / n
            val sxx = pts.sumOf { (it.y - mx) * (it.y - mx) }
            val syy = pts.sumOf { (it.value - my) * (it.value - my) }
            val sxy = pts.sumOf { (it.y - mx) * (it.value - my) }
            if (sxx <= 0.0 || syy <= 0.0) return null
            val r2 = (sxy * sxy) / (sxx * syy)
            if (r2 < minRSquared) return null
            val slope = sxy / sxx
            return PriceAxisCalibration(slope, my - slope * mx, r2)
        }
    }
}

/**
 * Result of reading candles from a chart image. [candles] are oldest -> newest; the LAST one is the candle that is
 * still forming and must not be analysed as closed. [confidence] is 0..1 and 0 means "do not use".
 */
data class ChartDetection(val candles: List<Candle>, val confidence: Double, val note: String, val pitchPx: Double = 0.0) {
    /** Only candles that have finished forming. */
    val closed: List<Candle> get() = if (candles.size > 1) candles.dropLast(1) else emptyList()
}

/**
 * Reads real open/high/low/close from the pixels of a chart (filled candles: green up, red down), so wicks and bodies
 * come from what is drawn instead of from sampled prices. Pure Kotlin on an ARGB array - no Android types.
 *
 * It refuses rather than guesses: merged candles, ragged spacing, too few candles or an unusable price scale all
 * return an empty detection with confidence 0. Colours and thresholds depend on the theme and must be checked on
 * real screenshots; until then use [agreesWith] to cross-check against the OCR live price.
 */
class ChartCandleDetector(
    private val minCandles: Int = 5,
    private val minBodyWidthPx: Int = 3,
    private val colourMargin: Int = 60,
    private val uniformTolerance: Double = 0.30
) {
    private enum class Ink { UP, DOWN, NONE }

    private fun ink(argb: Int): Ink {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return when {
            g >= MIN_BRIGHT_UP && g > r + colourMargin && g > b + colourMargin / 2 -> Ink.UP
            r >= MIN_BRIGHT_DOWN && r > g + colourMargin && r > b + colourMargin -> Ink.DOWN
            else -> Ink.NONE
        }
    }

    private class Column(var ink: Ink = Ink.NONE, var minY: Int = Int.MAX_VALUE, var maxY: Int = -1)

    /**
     * @param plotRight columns at or beyond this x are ignored (the price axis).
     * @param rightmostOpenTimeMs open time to give the rightmost (forming) candle; older ones step back by [candleMs].
     */
    fun detect(
        pixels: IntArray, width: Int, height: Int, plotRight: Int,
        calibration: PriceAxisCalibration?, rightmostOpenTimeMs: Long, candleMs: Long,
        rightEdgeOnly: Boolean = true,
        plotBottom: Int = height
    ): ChartDetection {
        fun none(why: String) = ChartDetection(emptyList(), 0.0, why)
        if (calibration == null) return none("Price scale could not be calibrated.")
        if (width <= 0 || height <= 0 || pixels.size < width * height) return none("Bad image size.")
        val right = plotRight.coerceIn(1, width)

        // Only the plot area counts: nothing below the time axis (Buy / Sell buttons, trade panel), and no row that is
        // mostly inked across the width (promo banner, buttons): candles never fill half of a row's width.
        val bottom = plotBottom.coerceIn(1, height)
        val barRow = BooleanArray(height)
        for (y in 0 until bottom) {
            var inked = 0
            for (x in 0 until right) if (ink(pixels[y * width + x]) != Ink.NONE) inked++
            barRow[y] = inked > right * BAR_ROW_FRACTION
        }
        val cols = Array(right) { Column() }
        for (x in 0 until right) {
            var up = 0; var down = 0
            val c = cols[x]
            for (y in 0 until bottom) {
                if (barRow[y]) continue
                val k = ink(pixels[y * width + x])
                if (k == Ink.NONE) continue
                if (k == Ink.UP) up++ else down++
                if (y < c.minY) c.minY = y
                if (y > c.maxY) c.maxY = y
            }
            c.ink = when { up == 0 && down == 0 -> Ink.NONE; up >= down -> Ink.UP; else -> Ink.DOWN }
        }

        // Runs of consecutive same-coloured columns. One run is one candle, or several touching candles of one colour.
        class Run(val ink: Ink, val x0: Int, val x1: Int)
        val runs = ArrayList<Run>()
        var x = 0
        while (x < right) {
            val c = cols[x]
            if (c.ink == Ink.NONE) { x++; continue }
            var e = x
            while (e + 1 < right && cols[e + 1].ink == c.ink) e++
            runs.add(Run(c.ink, x, e))
            x = e + 1
        }
        val sized = runs.filter { it.x1 - it.x0 + 1 >= minBodyWidthPx }
        if (sized.isEmpty()) return none("No green/red candle bodies found in the chart area (${runs.size} thin shapes only).")

        // Typical single-candle width = LOWER quartile of the run widths, so merged (over-wide) runs cannot inflate it.
        val widths = sized.map { it.x1 - it.x0 + 1 }.sorted()
        val medW = widths[widths.size / 4].toDouble()

        // A run that is a whole multiple of the typical width is several touching candles: cut it into equal slots.
        // Runs much narrower than a candle (price tag, line fragments) are junk and ignored.
        class Slot(val ink: Ink, val x0: Int, val x1: Int, val single: Boolean) { val centre get() = (x0 + x1) / 2.0 }
        val slots = ArrayList<Slot?>() // null = a run that is neither one candle nor a clean multiple: a barrier
        for (r in sized) {
            val w = r.x1 - r.x0 + 1
            if (w < medW * 0.6) continue
            val n = maxOf(1, (w / medW).roundToInt())
            if (n > 1 && abs(w.toDouble() / n - medW) > medW * 0.35) { slots.add(null); continue }
            for (i in 0 until n) {
                val a = r.x0 + (i * w.toDouble() / n).roundToInt()
                val b = r.x0 + ((i + 1) * w.toDouble() / n).roundToInt() - 1
                slots.add(Slot(r.ink, a, maxOf(a, b), n == 1))
            }
        }
        val real = slots.filterNotNull()
        if (real.size < minCandles) return none("Only ${real.size} candles found (need $minCandles).")
        // Gaps inside a merged block are artificial (one candle width), so the typical spacing is measured between
        // candles that were drawn as separate runs. Fall back to all gaps only when there are too few of those.
        val cleanGaps = real.zipWithNext().filter { (a, b) -> a.single && b.single }.map { (a, b) -> b.centre - a.centre }
        val sortedGaps = (if (cleanGaps.size >= 2) cleanGaps else real.zipWithNext { a, b -> b.centre - a.centre }).sorted()
        val medGap = sortedGaps[sortedGaps.size / 4]
        val pitch = sortedGaps[sortedGaps.size / 2]

        // Only the newest candles matter. Walk in from the right and stop at the first barrier or hole;
        // everything to its left is ignored, never guessed.
        val good: List<Slot> = if (rightEdgeOnly) {
            val goodRev = ArrayList<Slot>()
            for (i in slots.indices.reversed()) {
                val s = slots[i] ?: break
                val prev = goodRev.lastOrNull()
                if (prev != null && prev.centre - s.centre > medGap * 1.6 + 1.0) break
                goodRev.add(s)
            }
            goodRev.reversed()
        } else {
            // History scan: the chart is scrolled back, so the newest candle is not at the right edge.
            // Take the longest unbroken stretch anywhere in the frame.
            var best: List<Slot> = emptyList()
            var cur = ArrayList<Slot>()
            for (sl in slots) {
                if (sl == null) { if (cur.size > best.size) best = cur; cur = ArrayList(); continue }
                val prev = cur.lastOrNull()
                if (prev != null && sl.centre - prev.centre > medGap * 1.6 + 1.0) { if (cur.size > best.size) best = cur; cur = ArrayList() }
                cur.add(sl)
            }
            if (cur.size > best.size) best = cur
            best
        }
        if (good.size < minCandles) {
            return none(
                if (good.size < real.size) "Only ${good.size} clean candles at the right edge (need $minCandles); older ones are merged or have holes."
                else "Only ${good.size} candles found (need $minCandles)."
            )
        }

        val candles = ArrayList<Candle>(good.size)
        var uniform = 0
        for ((i, slot) in good.withIndex()) {
            val w = slot.x1 - slot.x0 + 1
            // Body = rows where most of the slot is inked; wicks are only 1-2 columns wide so they do not count.
            val need = maxOf(1, ceil(w * 0.6).toInt())
            var bodyTop = -1; var bodyBottom = -1
            for (y in 0 until bottom) {
                if (barRow[y]) continue
                var n = 0
                for (cx in slot.x0..slot.x1) if (ink(pixels[y * width + cx]) == slot.ink) n++
                if (n >= need) { if (bodyTop < 0) bodyTop = y; bodyBottom = y + 1 }
            }
            if (bodyTop < 0) return none("Candle ${i + 1} has no solid body (spacing or colours not recognised).")
            var wickTop = Int.MAX_VALUE; var wickBottom = -1
            for (cx in slot.x0..slot.x1) { wickTop = minOf(wickTop, cols[cx].minY); wickBottom = maxOf(wickBottom, cols[cx].maxY + 1) }
            val hi = calibration.priceAt(wickTop.toDouble())
            val lo = calibration.priceAt(wickBottom.toDouble())
            val top = calibration.priceAt(bodyTop.toDouble())
            val bottom = calibration.priceAt(bodyBottom.toDouble())
            val (open, close) = if (slot.ink == Ink.UP) bottom to top else top to bottom
            val openTime = rightmostOpenTimeMs - (good.size - 1 - i) * candleMs
            candles.add(Candle(openTime, open, maxOf(maxOf(hi, lo), maxOf(open, close)), minOf(minOf(hi, lo), minOf(open, close)), close))
            val widthOk = abs(w - medW) <= maxOf(medW * uniformTolerance, 1.5)
            val gapOk = i == 0 || abs((slot.centre - good[i - 1].centre) - medGap) <= maxOf(medGap * uniformTolerance, 1.5)
            if (widthOk && gapOk) uniform++
        }
        val uniformity = uniform.toDouble() / good.size
        if (uniformity < 0.8) return none("Candle spacing is ragged (${(uniformity * 100).toInt()}% regular).")
        // Higher price must be higher on screen (smaller y); a flipped fit means the axis was misread.
        if (calibration.priceAt(0.0) < calibration.priceAt((height - 1).toDouble())) return none("Price scale runs the wrong way.")
        val confidence = (uniformity * calibration.rSquared).coerceIn(0.0, 1.0)
        return ChartDetection(candles, confidence, "OK, ${candles.size} candles read from the chart.", pitch)
    }

    companion object {
        /** A row inked over more than this share of the plot width is a banner / button, not candles. */
        private const val BAR_ROW_FRACTION = 0.45
        private const val MIN_BRIGHT_UP = 110
        private const val MIN_BRIGHT_DOWN = 140

        /** True when the forming candle's close matches the OCR live price within [tolerance] (absolute price units). */
        fun agreesWith(detection: ChartDetection, ocrLivePrice: Double, tolerance: Double): Boolean {
            val last = detection.candles.lastOrNull() ?: return false
            return abs(last.close - ocrLivePrice) <= tolerance
        }
    }
}
