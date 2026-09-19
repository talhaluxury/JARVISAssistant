package com.jarvis.assistant.trading

/**
 * PHASE 3 — MULTI-TIMEFRAME CONFLUENCE ENGINE (spec §3)
 *
 * "Do not enter a trade based on a single timeframe alone." This engine's only job is to run
 * [MarketStructureEngine] independently on each timeframe in the configured stack and report
 * whether they agree — it does not itself decide anything about entries; that's
 * [SignalConfidenceEngine] (Phase 3's other half), which treats a [MultiTimeframeAlignment] of
 * CONFLICTING as a hard NO_TRADE per spec §2 ("if indicators conflict, return NO_TRADE").
 */

data class TimeframeStructureSnapshot(
    val timeframe: Timeframe,
    val structure: MarketStructureSnapshot,
    val candles: List<Candle>
)

enum class TrendDirection { UP, DOWN, FLAT }

enum class MultiTimeframeAlignment { ALIGNED_BULLISH, ALIGNED_BEARISH, CONFLICTING, NO_CLEAR_DIRECTION }

fun MarketRegime.toTrendDirection(): TrendDirection = when (this) {
    MarketRegime.TRENDING_UP -> TrendDirection.UP
    MarketRegime.TRENDING_DOWN -> TrendDirection.DOWN
    MarketRegime.RANGING, MarketRegime.UNCERTAIN -> TrendDirection.FLAT
}

/** RANGING/UNCERTAIN timeframes are excluded from the agreement check rather than treated as a
 * third opposing vote — a 1D chart chopping sideways shouldn't by itself veto a clean
 * 4H/1H/15M uptrend. But if every timeframe is flat, or the directional ones disagree with each
 * other, that's exactly the "conflicting timeframes" case spec §2 requires NO_TRADE for.
 *
 * Kept as a standalone pure function (not a method requiring a live [MultiTimeframeEngine]
 * instance) so [SignalConfidenceEngine] and tests can compute it from a list of snapshots —
 * built however the caller likes — without needing a [MarketDataService] in scope. */
fun computeAlignment(snapshots: List<TimeframeStructureSnapshot>): MultiTimeframeAlignment {
    val directions = snapshots.map { it.structure.regime.toTrendDirection() }.filter { it != TrendDirection.FLAT }
    return when {
        directions.isEmpty() -> MultiTimeframeAlignment.NO_CLEAR_DIRECTION
        directions.all { it == TrendDirection.UP } -> MultiTimeframeAlignment.ALIGNED_BULLISH
        directions.all { it == TrendDirection.DOWN } -> MultiTimeframeAlignment.ALIGNED_BEARISH
        else -> MultiTimeframeAlignment.CONFLICTING
    }
}

class MultiTimeframeEngine(
    private val marketDataService: MarketDataService,
    private val structureEngine: MarketStructureEngine,
    private val candlesPerTimeframe: Int = 150
) {
    /** Pulls and analyzes candles for every timeframe in [stack]. [levelTolerance] is supplied
     * per-timeframe by the caller (typically a multiple of that timeframe's ATR) rather than
     * computed here, keeping this engine free of a hard dependency on TechnicalIndicators. */
    suspend fun analyzeStack(
        pair: CurrencyPair,
        stack: List<Timeframe>,
        levelTolerance: (List<Candle>) -> Double
    ): List<TimeframeStructureSnapshot> = stack.map { tf ->
        val candles = marketDataService.candles(pair, tf, candlesPerTimeframe)
        val tolerance = if (candles.isEmpty()) 0.0 else levelTolerance(candles)
        TimeframeStructureSnapshot(tf, structureEngine.analyze(candles, tolerance), candles)
    }

    fun alignment(snapshots: List<TimeframeStructureSnapshot>): MultiTimeframeAlignment = computeAlignment(snapshots)
}
