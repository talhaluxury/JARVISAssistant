package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Section 3: how much the analysis may trust the candles it was given. */
enum class DataQuality { EXCELLENT, GOOD, FAIR, POOR }

data class DataIssue(val code: String, val detail: String, val severe: Boolean)

data class DataValidationReport(
    val quality: DataQuality,
    val issues: List<DataIssue>,
    val candleCount: Int
) {
    /** POOR data always means NO TRADE; FAIR is allowed but should lower how much a setup is trusted. */
    val tradable: Boolean get() = quality != DataQuality.POOR

    fun summary(): String =
        if (issues.isEmpty()) "Data quality ${quality.name}: no problems found in $candleCount candles."
        else "Data quality ${quality.name}: " + issues.joinToString("; ") { it.detail }
}

/**
 * Checks candle sequence, timestamps, OHLC sanity, gaps, duplicates, stale prices, abnormal jumps and (when the
 * candles came from screen reading) OCR confidence. Pure and deterministic, so it is fully unit-testable.
 */
class DataValidator(
    private val candleMs: Long,
    private val minCandles: Int = 150,
    private val maxJumpFraction: Double = 0.02,
    /** Only the newest candles decide quality; very old problems no longer matter to a live read. */
    private val recentWindow: Int = 60,
    private val staleAfterCandles: Int = 3,
    private val flatRunLength: Int = 10
) {
    fun validate(candles: List<Candle>, nowMs: Long? = null, ocrConfidence: Double? = null): DataValidationReport {
        val issues = ArrayList<DataIssue>()
        val n = candles.size
        if (n < minCandles) {
            issues.add(DataIssue("INSUFFICIENT", "only $n of $minCandles candles collected", true))
        }
        val start = max(0, n - recentWindow)

        var badOhlc = 0
        for (i in start until n) {
            val c = candles[i]
            val values = doubleArrayOf(c.open, c.high, c.low, c.close)
            val finite = values.all { !it.isNaN() && !it.isInfinite() && it > 0.0 }
            val consistent = finite && c.high >= max(c.open, c.close) && c.low <= min(c.open, c.close) && c.high >= c.low
            if (!consistent) badOhlc++
        }
        if (badOhlc > 0) issues.add(DataIssue("BAD_OHLC", "$badOhlc candle(s) with invalid OHLC values", true))

        var duplicates = 0
        var outOfOrder = 0
        var gaps = 0
        for (i in max(1, start) until n) {
            val delta = candles[i].openTimeMs - candles[i - 1].openTimeMs
            when {
                delta == 0L -> duplicates++
                delta < 0L -> outOfOrder++
                candleMs > 0L && delta > candleMs * 3 / 2 -> gaps++
            }
        }
        if (duplicates > 0) issues.add(DataIssue("DUPLICATE", "$duplicates duplicate candle timestamp(s)", true))
        if (outOfOrder > 0) issues.add(DataIssue("OUT_OF_ORDER", "$outOfOrder candle(s) out of time order", true))
        if (gaps > 0) issues.add(DataIssue("GAPS", "$gaps gap(s) with missing candles", gaps >= 3))

        var jumps = 0
        var recentJump = false
        for (i in max(1, start) until n) {
            val prev = candles[i - 1].close
            if (prev > 0.0 && abs(candles[i].open - prev) / prev > maxJumpFraction) {
                jumps++
                if (i >= n - 3) recentJump = true
            }
        }
        if (jumps > 0) issues.add(DataIssue("ABNORMAL_JUMP", "$jumps abnormal price jump(s) between candles", recentJump))

        if (n >= flatRunLength) {
            val tail = candles.subList(n - flatRunLength, n)
            val flat = tail.all { it.high == it.low && it.open == it.close && it.close == tail[0].close }
            if (flat) issues.add(DataIssue("FLAT_PRICE", "price has not moved for $flatRunLength candles (stale feed?)", true))
        }

        if (nowMs != null && n > 0 && candleMs > 0L) {
            val age = nowMs - candles[n - 1].openTimeMs
            if (age > candleMs * (staleAfterCandles + 1)) {
                issues.add(DataIssue("STALE", "newest candle is ${age / 1000}s old", true))
            }
        }

        if (ocrConfidence != null) {
            when {
                ocrConfidence < 0.5 -> issues.add(DataIssue("OCR_LOW", "screen reading confidence only ${(ocrConfidence * 100).toInt()}%", true))
                ocrConfidence < 0.75 -> issues.add(DataIssue("OCR_FAIR", "screen reading confidence ${(ocrConfidence * 100).toInt()}%", false))
            }
        }

        val severe = issues.count { it.severe }
        val mild = issues.size - severe
        val quality = when {
            severe > 0 -> DataQuality.POOR
            mild == 0 -> DataQuality.EXCELLENT
            mild == 1 -> DataQuality.GOOD
            mild <= 3 -> DataQuality.FAIR
            else -> DataQuality.POOR
        }
        return DataValidationReport(quality, issues, n)
    }
}
