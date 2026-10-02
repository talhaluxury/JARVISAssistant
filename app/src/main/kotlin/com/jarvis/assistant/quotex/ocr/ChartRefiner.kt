package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs

/**
 * Decides whether a candle read from the chart image may replace the candle JARVIS built from sampled OCR prices.
 * Conservative on purpose: the chart candle is used only when its open and close agree with the sampled candle
 * (same timeframe, same bar, sane calibration) and its wicks do not stretch implausibly far. Otherwise the sampled
 * candle is kept untouched - the chart can improve high/low, it can never inject a doubtful bar.
 */
object ChartRefiner {
    /** Relative disagreement allowed between sampled and chart open/close (0.03% ~ 3 pips on EUR/USD). */
    const val OPEN_CLOSE_TOLERANCE = 0.0003
    /** Longest wick the chart may add beyond the body, relative to price (0.2%). */
    const val MAX_WICK_FRACTION = 0.002

    fun refine(sampled: Candle, chart: Candle?): Candle {
        if (chart == null || chart.openTimeMs != sampled.openTimeMs) return sampled
        val ref = sampled.close
        if (ref <= 0.0) return sampled
        val tol = ref * OPEN_CLOSE_TOLERANCE
        if (abs(chart.open - sampled.open) > tol || abs(chart.close - sampled.close) > tol) return sampled
        val bodyTop = maxOf(chart.open, chart.close)
        val bodyBottom = minOf(chart.open, chart.close)
        val maxWick = ref * MAX_WICK_FRACTION
        if (chart.high - bodyTop > maxWick || bodyBottom - chart.low > maxWick) return sampled
        if (!(chart.high >= bodyTop && chart.low <= bodyBottom && chart.low > 0.0)) return sampled
        return chart
    }

}
