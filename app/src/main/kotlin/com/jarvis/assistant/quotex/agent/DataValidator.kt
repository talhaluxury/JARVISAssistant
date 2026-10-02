package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs

/** Section 3: how much the analysis may trust the candles it is looking at. */
enum class DataQuality { EXCELLENT, GOOD, FAIR, POOR }

data class DataIssue(val code: String, val detail: String, val severe: Boolean)

data class DataValidationReport(
    val quality: DataQuality,
    val issues: List<DataIssue>,
    val candleCount: Int
) {
    /** POOR data always means NO TRADE (section 3). */
    val tradable: Boolean get() = quality != DataQuality.POOR
    val summary: String
        get() = if (issues.isEmpty()) "Data quality ${quality.name}: no issues found."
        else "Data quality ${quality.name}: " + issues.joinToString("; ") { it.detail }
}

/**
 * Checks candle sequence, timestamps, OHLC sanity, gaps, duplicates, stale prices, abnormal jumps and
 * (optionally) OCR confidence. Pure function of its inputs - never looks at the clock unless [nowMs] is given.
 */
class DataValidator(
    private val candleMs: Long,
    private val minCandles: Int = 150,
    private val maxJumpFraction: Double = 0.02,
    private val staleAfterCandles: Int = 3,
    private val minOcrConfidence: Double = 0.6
) {
    fun validate(candles: List<Candle>, nowMs: Long? = null, ocrConfidence: Double? = null): DataValidationReport {
        val issues = ArrayList<DataIssue>()
        if (candles.isEmpty()) {
            return DataValidationReport(DataQuality.POOR, listOf(DataIssue("EMPTY", "No candles available", true)), 0)
        }

        var badOhlc = 0
        var duplicates = 0
        var outOfOrder = 0
        var gaps = 0
        var jumps = 0
        for (i in candles.indices) {
            val c = candles[i]
            val finite = c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite()
            val positive = c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0
            val consistent = c.high >= maxOf(c.open, c.close) && c.low <= minOf(c.open, c.close) && c.high >= c.low
            if (!finite || !positive || !consistent) badOhlc++
            if (i > 0) {
                val prev = candles[i - 1]
                val dt = c.openTimeMs - prev.openTimeMs
                when {
                    dt == 0L -> duplicates++
                    dt < 0L -> outOfOrder++
                    dt > candleMs * 3 / 2 -> gaps++
                }
                if (prev.close > 0.0 && abs(c.open - prev.close) / prev.close > maxJumpFraction) jumps++
            }
        }

        if (badOhlc > 0) issues.add(DataIssue("BAD_OHLC", "$badOhlc candle(s) with invalid OHLC values", true))
        if (outOfOrder > 0) issues.add(DataIssue("OUT_OF_ORDER", "$outOfOrder candle(s) out of time order", true))
        if (duplicates > 0) issues.add(DataIssue("DUPLICATE", "$duplicates duplicate candle timestamp(s)", false))
        if (gaps > 0) issues.add(DataIssue("GAPS", "$gaps gap(s) with missing candles", gaps > 3))
        if (jumps > 0) issues.add(DataIssue("JUMPS", "$jumps abnormal price jump(s) between candles", jumps > 2))
        if (candles.size < minCandles) {
            issues.add(DataIssue("TOO_FEW", "Only ${candles.size}/$minCandles candles", true))
        }

        val tail = candles.takeLast(staleAfterCandles + 1)
        if (tail.size > staleAfterCandles && tail.all { it.high == it.low && it.open == it.close && it.close == tail[0].close }) {
            issues.add(DataIssue("STALE_PRICE", "Price has not moved for ${tail.size} candles", true))
        }
        if (nowMs != null) {
            val ageMs = nowMs - candles.last().openTimeMs
            if (ageMs > candleMs * (staleAfterCandles + 1)) {
                issues.add(DataIssue("STALE_FEED", "Newest candle is ${ageMs / 1000}s old", true))
            }
        }
        if (ocrConfidence != null && ocrConfidence < minOcrConfidence) {
            issues.add(DataIssue("LOW_OCR", "OCR confidence ${(ocrConfidence * 100).toInt()}% is below ${(minOcrConfidence * 100).toInt()}%", true))
        }

        val quality = when {
            issues.any { it.severe } -> DataQuality.POOR
            issues.size >= 2 -> DataQuality.FAIR
            issues.size == 1 -> DataQuality.GOOD
            else -> DataQuality.EXCELLENT
        }
        return DataValidationReport(quality, issues, candles.size)
    }
}
