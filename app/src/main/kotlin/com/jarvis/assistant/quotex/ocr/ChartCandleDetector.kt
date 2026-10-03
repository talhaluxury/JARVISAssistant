package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs

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
data class ChartDetection(val candles: List<Candle>, val confidence: Double, val note: String) {
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
    private val minCandles: Int = 8,
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
        calibration: PriceAxisCalibration?, rightmostOpenTimeMs: Long, candleMs: Long
    ): ChartDetection {
        fun none(why: String) = ChartDetection(emptyList(), 0.0, why)
        if (calibration == null) return none("Price scale could not be calibrated.")
        if (width <= 0 || height <= 0 || pixels.size < width * height) return none("Bad image size.")
        val right = plotRight.coerceIn(1, width)

        val cols = Array(right) { Column() }
        for (x in 0 until right) {
            var up = 0; var down = 0
            val c = cols[x]
            for (y in 0 until height) {
                val k = ink(pixels[y * width + x])
                if (k == Ink.NONE) continue
                if (k == Ink.UP) up++ else down++
                if (y < c.minY) c.minY = y
                if (y > c.maxY) c.maxY = y
            }
            c.ink = when { up == 0 && down == 0 -> Ink.NONE; up >= down -> Ink.UP; else -> Ink.DOWN }
        }

        // Runs of consecutive same-coloured columns = one candle each.
        class Run(val ink: Ink, val x0: Int, var x1: Int)
        val runs = ArrayList<Run>()
        var x = 0
        while (x < right) {
            val c = cols[x]
            if (c.ink == Ink.NONE) { x++; continue }
            val run = Run(c.ink, x, x)
            while (run.x1 + 1 < right && cols[run.x1 + 1].ink == c.ink) run.x1++
            runs.add(run)
            x = run.x1 + 1
        }
        val split = ArrayList<Run>()
        for (r in runs) for ((a, b) in splitRun(cols, r.x0, r.x1)) split.add(Run(r.ink, a, b))
        val wide = split.filter { it.x1 - it.x0 + 1 >= minBodyWidthPx }
        if (wide.size < minCandles) return none("Only ${wide.size} candles found (need $minCandles).")

        val widths = wide.map { it.x1 - it.x0 + 1 }.sorted()
        val medW = widths[widths.size / 2].toDouble()
        val centres = wide.map { (it.x0 + it.x1) / 2.0 }
        val gaps = centres.zipWithNext { a, b -> b - a }.sorted()
        val medGap = gaps[gaps.size / 2]
        if (wide.any { (it.x1 - it.x0 + 1) > medW * 1.6 }) return none("Adjacent candles merged; zoom the chart in.")

        val candles = ArrayList<Candle>(wide.size)
        var uniform = 0
        for ((i, run) in wide.withIndex()) {
            val w = run.x1 - run.x0 + 1
            val edgeA = cols[run.x0]
            val edgeB = cols[run.x1]
            // Wicks sit in the middle, so the outermost columns hold the body only.
            val bodyTop = minOf(edgeA.minY, edgeB.minY)
            val bodyBottom = maxOf(edgeA.maxY, edgeB.maxY) + 1
            var wickTop = Int.MAX_VALUE; var wickBottom = -1
            for (cx in run.x0..run.x1) { wickTop = minOf(wickTop, cols[cx].minY); wickBottom = maxOf(wickBottom, cols[cx].maxY + 1) }
            val hi = calibration.priceAt(wickTop.toDouble())
            val lo = calibration.priceAt(wickBottom.toDouble())
            val top = calibration.priceAt(bodyTop.toDouble())
            val bottom = calibration.priceAt(bodyBottom.toDouble())
            val (open, close) = if (run.ink == Ink.UP) bottom to top else top to bottom
            val openTime = rightmostOpenTimeMs - (wide.size - 1 - i) * candleMs
            candles.add(Candle(openTime, open, maxOf(maxOf(hi, lo), maxOf(open, close)), minOf(minOf(hi, lo), minOf(open, close)), close))
            val widthOk = abs(w - medW) <= medW * uniformTolerance
            val gapOk = i == 0 || abs((centres[i] - centres[i - 1]) - medGap) <= medGap * uniformTolerance
            if (widthOk && gapOk) uniform++
        }
        val uniformity = uniform.toDouble() / wide.size
        if (uniformity < 0.8) return none("Candle spacing is ragged (${(uniformity * 100).toInt()}% regular).")
        // Higher price must be higher on screen (smaller y); a flipped fit means the axis was misread.
        if (calibration.priceAt(0.0) < calibration.priceAt((height - 1).toDouble())) return none("Price scale runs the wrong way.")
        val confidence = (uniformity * calibration.rSquared).coerceIn(0.0, 1.0)
        return ChartDetection(candles, confidence, "OK, ${candles.size} candles read from the chart.")
    }

    /**
     * Touching candles of the same colour form one wide run. Their bodies usually differ in height, so a run is cut
     * wherever the vertical extent jumps; a thin column that sticks out past both neighbours is a wick and is glued
     * back to its own body. Identical neighbouring bodies cannot be told apart and stay merged (-> detector refuses).
     */
    private fun splitRun(cols: Array<Column>, x0: Int, x1: Int): List<Pair<Int, Int>> {
        val tol = 2
        val segs = ArrayList<IntArray>() // [start, end]
        var start = x0
        for (x in x0 + 1..x1) {
            if (abs(cols[x].minY - cols[x - 1].minY) > tol || abs(cols[x].maxY - cols[x - 1].maxY) > tol) {
                segs.add(intArrayOf(start, x - 1)); start = x
            }
        }
        segs.add(intArrayOf(start, x1))
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < segs.size) {
            if (i + 2 < segs.size) {
                val a = segs[i]; val w = segs[i + 1]; val c = segs[i + 2]
                val wickWidth = w[1] - w[0] + 1
                val ca = cols[a[0]]; val cw = cols[w[0]]; val cc = cols[c[0]]
                val sides = abs(ca.minY - cc.minY) <= tol && abs(ca.maxY - cc.maxY) <= tol
                val sticksOut = cw.minY <= minOf(ca.minY, cc.minY) + tol && cw.maxY >= maxOf(ca.maxY, cc.maxY) - tol
                if (wickWidth <= 2 && sides && sticksOut) { out.add(intArrayOf(a[0], c[1])); i += 3; continue }
            }
            out.add(segs[i]); i++
        }
        return out.map { it[0] to it[1] }
    }

    companion object {
        private const val MIN_BRIGHT_UP = 110
        private const val MIN_BRIGHT_DOWN = 140

        /** True when the forming candle's close matches the OCR live price within [tolerance] (absolute price units). */
        fun agreesWith(detection: ChartDetection, ocrLivePrice: Double, tolerance: Double): Boolean {
            val last = detection.candles.lastOrNull() ?: return false
            return abs(last.close - ocrLivePrice) <= tolerance
        }
    }
}
