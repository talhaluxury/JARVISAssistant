package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.trading.QuotexDecision

/** How strong the combined evidence from the strategy library currently is. Descriptive, never a promise. */
enum class SetupQuality { NO_SETUP, WEAK_SETUP, WATCH, SETUP_DETECTED, HIGH_CONFLUENCE_SETUP }

data class ConfluenceResult(
    val direction: QuotexDecision,
    val quality: SetupQuality,
    val agreeingStrategies: Int,
    val totalStrategies: Int,
    val conditionsSatisfied: Int,
    val conditionsTotal: Int,
    val strategyResults: List<StrategyResult>,
    val volatility: VolatilityState,
    val trend: TrendState,
    val structure: StructureLabel,
    val reason: String
)

/**
 * Combines the strategy library into one setup-quality read. No single strategy or indicator can produce
 * a setup by itself - a setup only exists when independent strategies agree on the same direction with
 * their own conditions largely satisfied, and extreme volatility always caps the result.
 *
 * [weights] lets calibration (section 21) reduce a strategy's influence once its own walk-forward record
 * turns poor, via [StrategyPerformanceTracker.weight]. Missing entries default to 1.0, so passing nothing
 * reproduces the original equal-weight behaviour exactly.
 */
class ConfluenceEngine(private val strategies: List<Strategy> = defaultStrategies(), private val weights: Map<String, Double> = emptyMap()) {

    private fun weightOf(strategyName: String): Double = weights[strategyName] ?: 1.0

    fun evaluate(series: PriceSeries, trend: TrendState, structure: StructureLabel, volatility: VolatilityState): ConfluenceResult {
        val swings = MarketStructure.swings(series)
        val results = strategies.map { it.evaluate(series, trend, structure, volatility, swings) }
        val leaning = results.filter { it.direction != QuotexDecision.WAIT }

        if (leaning.isEmpty()) {
            return ConfluenceResult(
                QuotexDecision.WAIT, SetupQuality.NO_SETUP, 0, strategies.size, 0, 0, results,
                volatility, trend, structure, "No strategy in the library currently sees a setup."
            )
        }

        val callGroup = leaning.filter { it.direction == QuotexDecision.CALL }
        val putGroup = leaning.filter { it.direction == QuotexDecision.PUT }
        val callWeight = callGroup.sumOf { weightOf(it.strategyName) }
        val putWeight = putGroup.sumOf { weightOf(it.strategyName) }
        val (direction, group) = if (callWeight >= putWeight) {
            Pair(QuotexDecision.CALL, callGroup)
        } else {
            Pair(QuotexDecision.PUT, putGroup)
        }
        if (callGroup.isNotEmpty() && putGroup.isNotEmpty() && kotlin.math.abs(callWeight - putWeight) < 1e-9) {
            return ConfluenceResult(
                QuotexDecision.WAIT, SetupQuality.NO_SETUP, 0, strategies.size,
                leaning.sumOf { it.satisfiedCount }, leaning.sumOf { it.totalCount }, results,
                volatility, trend, structure,
                "Strategies disagree on direction (${callGroup.size} CALL vs ${putGroup.size} PUT) - no setup."
            )
        }

        val groupWeightSum = group.sumOf { weightOf(it.strategyName) }
        val avgScore = if (groupWeightSum <= 0.0) group.map { it.score }.average() else
            group.sumOf { it.score * weightOf(it.strategyName) } / groupWeightSum
        val conditionsSatisfied = group.sumOf { it.satisfiedCount }
        val conditionsTotal = group.sumOf { it.totalCount }

        var quality = when {
            group.size == 1 && avgScore < 0.75 -> SetupQuality.WEAK_SETUP
            group.size == 1 -> SetupQuality.WATCH
            group.size == 2 && avgScore < 0.75 -> SetupQuality.WATCH
            group.size == 2 -> SetupQuality.SETUP_DETECTED
            else -> if (avgScore >= 0.75) SetupQuality.HIGH_CONFLUENCE_SETUP else SetupQuality.SETUP_DETECTED
        }

        // Section 9: abnormal volatility always reduces quality, regardless of how many strategies agree.
        if (volatility == VolatilityState.EXTREME) {
            quality = when (quality) {
                SetupQuality.HIGH_CONFLUENCE_SETUP, SetupQuality.SETUP_DETECTED -> SetupQuality.WATCH
                SetupQuality.WATCH -> SetupQuality.WEAK_SETUP
                else -> SetupQuality.NO_SETUP
            }
        }

        val reason = "${group.size} of ${strategies.size} strategies lean ${direction.name} " +
            "(${group.joinToString(", ") { it.strategyName }}), average ${(avgScore * 100).toInt()}% of their conditions met." +
            if (volatility == VolatilityState.EXTREME) " Reduced for extreme volatility." else ""

        return ConfluenceResult(
            direction = if (quality == SetupQuality.NO_SETUP) QuotexDecision.WAIT else direction,
            quality = quality, agreeingStrategies = group.size, totalStrategies = strategies.size,
            conditionsSatisfied = conditionsSatisfied, conditionsTotal = conditionsTotal, strategyResults = results,
            volatility = volatility, trend = trend, structure = structure, reason = reason
        )
    }
}
