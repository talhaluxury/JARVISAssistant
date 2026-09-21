package com.jarvis.assistant.quotex.domain

import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.WinGoConfig

/** One price reading taken from the screen. */
data class PriceTick(val timeMs: Long, val price: Double)

data class Candle(
    val openTimeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
) {
    val up: Boolean get() = close > open
}

/**
 * Tunables for the Quotex analyzer. A "signal" here is only an analysis of past prices for a fixed
 * expiry ([expiryCandles] candles ahead); it never places or prepares a trade.
 */
data class QuotexConfig(
    val candleSeconds: Int = 15,
    val expiryCandles: Int = 4,
    val minCandlesForSignal: Int = 150,
    /** Fraction of the stake paid on a win (0.85 = 85%). Used only for break-even and P/L maths. */
    val payout: Double = 0.85,
    val lowThreshold: Double = 0.55,
    val mediumThreshold: Double = 0.60,
    val highThreshold: Double = 0.70,
    val requireVerifiedEdge: Boolean = true,
    val edgeMinSamples: Int = 100,
    val edgeZThreshold: Double = 2.33,
    val edgeLookback: Int = 500,
    val minWeightedAgreement: Double = 0.5,
    val modelCandleCap: Int = 1500,
    val maxCandlesKept: Int = 3000,
    val maxTickJumpFraction: Double = 0.02,
    val sampleIntervalMs: Long = 2000L,
    val searchIntervalMs: Long = 4000L,
    val missesBeforePause: Int = 5
) {
    init {
        require(candleSeconds >= 5) { "candleSeconds must be at least 5" }
        require(expiryCandles in 1..60) { "expiryCandles must be within 1..60" }
        require(payout in 0.3..1.0) { "payout must be within 0.3..1.0" }
        require(lowThreshold in 0.5..1.0 && mediumThreshold > lowThreshold && highThreshold > mediumThreshold && highThreshold <= 1.0) {
            "thresholds must satisfy 0.5 <= low < medium < high <= 1.0"
        }
    }

    val candleMs: Long get() = candleSeconds * 1000L
    val expirySeconds: Int get() = candleSeconds * expiryCandles

    /** Win rate needed to break even at this payout: stake lost on a miss, [payout] won on a hit. */
    val breakEvenAccuracy: Double get() = 1.0 / (1.0 + payout)

    fun levelFor(confidence: Double): ConfidenceLevel = toWinGoConfig().levelFor(confidence)

    fun toWinGoConfig(): WinGoConfig =
        WinGoConfig(lowThreshold = lowThreshold, mediumThreshold = mediumThreshold, highThreshold = highThreshold)
}
