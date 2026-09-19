package com.jarvis.assistant.trading

import kotlin.math.abs

/**
 * PHASE 2 — MARKET STRUCTURE ENGINE (spec §5)
 *
 * Pure price-action structure, deliberately independent of the indicator math in
 * TechnicalIndicators.kt (spec §5 lists this as its own engine). The (later) confluence engine
 * combines this with indicators/multi-timeframe results — this file only ever answers "what is
 * the price doing", never "should we trade".
 *
 * `MarketRegime` is the concrete implementation of spec §5's "the engine should distinguish
 * TRENDING MARKET from RANGING/UNCERTAIN MARKET" requirement.
 */

enum class SwingType { HIGH, LOW }

data class SwingPoint(val index: Int, val price: Double, val type: SwingType, val timestampEpochMillis: Long)

enum class StructureLabel { HIGHER_HIGH, HIGHER_LOW, LOWER_HIGH, LOWER_LOW }

data class LabeledSwing(val swing: SwingPoint, val label: StructureLabel?)

enum class MarketRegime { TRENDING_UP, TRENDING_DOWN, RANGING, UNCERTAIN }

enum class LevelType { SUPPORT, RESISTANCE }

data class SupportResistanceLevel(val price: Double, val type: LevelType, val touches: Int, val lastTouchIndex: Int)

enum class StructureEvent { BREAKOUT, FALSE_BREAKOUT, LIQUIDITY_SWEEP, SUPPORT_REJECTION, RESISTANCE_REJECTION }

data class StructureEventResult(val event: StructureEvent, val level: SupportResistanceLevel, val candleIndex: Int, val detail: String)

data class MarketStructureSnapshot(
    val regime: MarketRegime,
    val swings: List<SwingPoint>,
    val labeledSwings: List<LabeledSwing>,
    val supportLevels: List<SupportResistanceLevel>,
    val resistanceLevels: List<SupportResistanceLevel>,
    val recentEvents: List<StructureEventResult>
)

class MarketStructureEngine(
    private val swingLookback: Int = 2,
    private val trendSwingWindow: Int = 4,
    private val minTouchesForLevel: Int = 2
) {

    /** A candle at index i is a swing high/low if it's the local extreme within [swingLookback]
     * candles on both sides — the standard fractal definition, chosen over a fixed zig-zag
     * percentage because it needs no pair-specific tuning to work across EURUSD and XAUUSD alike. */
    fun detectSwingPoints(candles: List<Candle>): List<SwingPoint> {
        if (candles.size < swingLookback * 2 + 1) return emptyList()
        val swings = mutableListOf<SwingPoint>()
        for (i in swingLookback until candles.size - swingLookback) {
            val window = (i - swingLookback)..(i + swingLookback)
            val isHigh = window.all { j -> j == i || candles[j].high <= candles[i].high }
            val isLow = window.all { j -> j == i || candles[j].low >= candles[i].low }
            if (isHigh) swings += SwingPoint(i, candles[i].high, SwingType.HIGH, candles[i].openTimeEpochMillis)
            if (isLow) swings += SwingPoint(i, candles[i].low, SwingType.LOW, candles[i].openTimeEpochMillis)
        }
        return swings
    }

    /** Compares each swing to the most recent swing of the SAME type (spec §5's HH/HL/LH/LL). */
    fun labelSwings(swings: List<SwingPoint>): List<LabeledSwing> {
        val result = mutableListOf<LabeledSwing>()
        var lastHigh: SwingPoint? = null
        var lastLow: SwingPoint? = null
        for (swing in swings) {
            val label = when (swing.type) {
                SwingType.HIGH -> lastHigh?.let {
                    if (swing.price > it.price) StructureLabel.HIGHER_HIGH else StructureLabel.LOWER_HIGH
                }
                SwingType.LOW -> lastLow?.let {
                    if (swing.price > it.price) StructureLabel.HIGHER_LOW else StructureLabel.LOWER_LOW
                }
            }
            result += LabeledSwing(swing, label)
            when (swing.type) {
                SwingType.HIGH -> lastHigh = swing
                SwingType.LOW -> lastLow = swing
            }
        }
        return result
    }

    /** TRENDING_UP requires the most recent labeled swings to be exclusively HH/HL (an uptrend
     * making a lower low, even once, is no longer confirmed-trending by this definition — spec
     * §5 explicitly warns against "blindly applying trend strategies during sideways markets",
     * so this is intentionally strict rather than permissive). Mixed direction => RANGING.
     * Fewer than [trendSwingWindow] labeled swings => UNCERTAIN (not enough structure yet to
     * classify either way — never silently default to trending). */
    fun classifyRegime(labeledSwings: List<LabeledSwing>): MarketRegime {
        val labeled = labeledSwings.filter { it.label != null }
        if (labeled.size < trendSwingWindow) return MarketRegime.UNCERTAIN
        val recent = labeled.takeLast(trendSwingWindow).map { it.label }
        val bullishLabels = setOf(StructureLabel.HIGHER_HIGH, StructureLabel.HIGHER_LOW)
        val bearishLabels = setOf(StructureLabel.LOWER_HIGH, StructureLabel.LOWER_LOW)
        val allBullish = recent.all { it in bullishLabels }
        val allBearish = recent.all { it in bearishLabels }
        return when {
            allBullish -> MarketRegime.TRENDING_UP
            allBearish -> MarketRegime.TRENDING_DOWN
            else -> MarketRegime.RANGING
        }
    }

    /** Clusters swing points by price proximity into support/resistance zones. [tolerance] is an
     * absolute price distance (caller supplies it, typically derived from ATR — e.g. 0.5x ATR —
     * so this engine stays independent of the indicator math and works for any pair/timeframe
     * without hardcoded pip assumptions). Levels at/below the last close are SUPPORT, above are
     * RESISTANCE; a level touched fewer than [minTouchesForLevel] times is discarded as noise. */
    fun findSupportResistance(
        swings: List<SwingPoint>,
        currentPrice: Double,
        tolerance: Double
    ): Pair<List<SupportResistanceLevel>, List<SupportResistanceLevel>> {
        if (tolerance <= 0) return emptyList<SupportResistanceLevel>() to emptyList()
        val sorted = swings.sortedBy { it.price }
        val clusters = mutableListOf<MutableList<SwingPoint>>()
        for (swing in sorted) {
            val cluster = clusters.lastOrNull()
            if (cluster != null && abs(swing.price - cluster.last().price) <= tolerance) {
                cluster += swing
            } else {
                clusters += mutableListOf(swing)
            }
        }
        val levels = clusters
            .filter { it.size >= minTouchesForLevel }
            .map { cluster ->
                val avgPrice = cluster.sumOf { it.price } / cluster.size
                val lastTouch = cluster.maxOf { it.index }
                SupportResistanceLevel(
                    price = avgPrice,
                    type = if (avgPrice <= currentPrice) LevelType.SUPPORT else LevelType.RESISTANCE,
                    touches = cluster.size,
                    lastTouchIndex = lastTouch
                )
            }
        val (support, resistance) = levels.partition { it.type == LevelType.SUPPORT }
        return support.sortedByDescending { it.price } to resistance.sortedBy { it.price }
    }

    /** Examines only the most recent candle against each level. A close beyond the level (with
     * the prior candle closing on the near side) is a [StructureEvent.BREAKOUT]. A wick beyond
     * the level with the close snapping back to the near side is a [StructureEvent.FALSE_BREAKOUT]
     * — the same price behavior spec §5 separately calls a "liquidity sweep" when it happens at
     * a level that was itself a recent, barely-tested swing extreme (likely resting stop-orders),
     * so that case is tagged [StructureEvent.LIQUIDITY_SWEEP] instead when the level's touch
     * count is 1. A rejection (long wick into the level, small body, no close through) is
     * reported separately so the signal engine can treat "broke through" and "bounced off" as
     * distinct evidence rather than folding them into one "something happened at this level" flag. */
    fun detectEvents(
        candles: List<Candle>,
        levels: List<SupportResistanceLevel>,
        wickToBodyRejectionRatio: Double = 2.0
    ): List<StructureEventResult> {
        if (candles.size < 2) return emptyList()
        val current = candles.last()
        val prior = candles[candles.size - 2]
        val currentIndex = candles.size - 1
        val events = mutableListOf<StructureEventResult>()

        for (level in levels) {
            val priorOnNearSide = when (level.type) {
                LevelType.RESISTANCE -> prior.close <= level.price
                LevelType.SUPPORT -> prior.close >= level.price
            }
            if (!priorOnNearSide) continue

            val closedThrough = when (level.type) {
                LevelType.RESISTANCE -> current.close > level.price
                LevelType.SUPPORT -> current.close < level.price
            }
            val wickThrough = when (level.type) {
                LevelType.RESISTANCE -> current.high > level.price
                LevelType.SUPPORT -> current.low < level.price
            }

            when {
                closedThrough -> events += StructureEventResult(
                    StructureEvent.BREAKOUT, level, currentIndex,
                    "Close ${current.close} broke through ${level.type} at ${level.price} (${level.touches} prior touches)."
                )
                wickThrough -> {
                    val event = if (level.touches <= 1) StructureEvent.LIQUIDITY_SWEEP else StructureEvent.FALSE_BREAKOUT
                    events += StructureEventResult(
                        event, level, currentIndex,
                        "Wick pierced ${level.type} at ${level.price} but closed back at ${current.close}."
                    )
                }
                else -> {
                    val body = abs(current.close - current.open)
                    val rejectionEvent = when (level.type) {
                        LevelType.RESISTANCE -> {
                            val upperWick = current.high - maxOf(current.open, current.close)
                            if (upperWick > body * wickToBodyRejectionRatio && current.high >= level.price * 0.999)
                                StructureEvent.RESISTANCE_REJECTION else null
                        }
                        LevelType.SUPPORT -> {
                            val lowerWick = minOf(current.open, current.close) - current.low
                            if (lowerWick > body * wickToBodyRejectionRatio && current.low <= level.price * 1.001)
                                StructureEvent.SUPPORT_REJECTION else null
                        }
                    }
                    if (rejectionEvent != null) {
                        events += StructureEventResult(
                            rejectionEvent, level, currentIndex,
                            "Long wick rejected from ${level.type} near ${level.price}."
                        )
                    }
                }
            }
        }
        return events
    }

    /** Convenience end-to-end pass over a candle series, for callers (later confluence engine,
     * HUD) that just want "the current picture" without wiring the steps together themselves. */
    fun analyze(candles: List<Candle>, levelTolerance: Double): MarketStructureSnapshot {
        val swings = detectSwingPoints(candles)
        val labeled = labelSwings(swings)
        val regime = classifyRegime(labeled)
        val currentPrice = candles.lastOrNull()?.close ?: 0.0
        val (support, resistance) = findSupportResistance(swings, currentPrice, levelTolerance)
        val events = detectEvents(candles, support + resistance)
        return MarketStructureSnapshot(regime, swings, labeled, support, resistance, events)
    }
}
