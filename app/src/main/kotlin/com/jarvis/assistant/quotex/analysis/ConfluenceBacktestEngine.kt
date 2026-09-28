package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.domain.Fmt
import kotlin.math.sqrt

data class StrategyStat(val name: String, val setups: Int, val wins: Int, val losses: Int, val weight: Double) {
    val hitRate: Double? get() = if (setups == 0) null else wins.toDouble() / setups
}

data class OutcomeStreak(val wins: Int, val losses: Int, val current: Int)

data class ConfluenceBacktestReport(
    val totalCandles: Int,
    val evaluatedCandles: Int,
    val totalSetups: Int,
    /** SETUP_DETECTED or HIGH_CONFLUENCE_SETUP only - the confluence engine's own "valid setup" bar. */
    val validSetups: Int,
    val wins: Int,
    val losses: Int,
    val streaks: OutcomeStreak,
    val byQuality: Map<SetupQuality, Pair<Int, Int>>,
    val perStrategy: List<StrategyStat>,
    val verdict: String
) {
    val hitRate: Double? get() = if (validSetups == 0) null else wins.toDouble() / validSetups

    fun toText(): String = buildString {
        appendLine("CONFLUENCE BACKTEST (walk-forward, no look-ahead)")
        appendLine("----------------")
        appendLine("Candles in data: $totalCandles  (evaluated: $evaluatedCandles)")
        appendLine("Total setups seen (any quality): $totalSetups")
        appendLine("Valid setups (SETUP_DETECTED or higher): $validSetups")
        appendLine("Wins: $wins   Losses: $losses   Hit rate: ${hitRate?.let { Fmt.pct(it, 1) } ?: "n/a"}")
        appendLine("Max winning streak: ${streaks.wins}   Max losing streak: ${streaks.losses}")
        appendLine()
        appendLine("By setup quality:")
        for (q in SetupQuality.values()) {
            val (count, w) = byQuality[q] ?: Pair(0, 0)
            val rate = if (count == 0) "n/a" else Fmt.pct(w.toDouble() / count, 1)
            appendLine("  ${q.name}: $count setups, $rate hit rate")
        }
        appendLine()
        appendLine("Strategy performance (own directional calls, independent of confluence gating):")
        for (s in perStrategy) {
            appendLine("  ${s.name}: ${s.hitRate?.let { Fmt.pct(it, 1) } ?: "n/a"} (${s.wins}/${s.setups})  weight ${Fmt.num(s.weight, 2)}")
        }
        appendLine()
        appendLine("Verdict: $verdict")
    }.trim()

    fun shortText(): String {
        val rate = hitRate?.let { Fmt.pct(it, 1) } ?: "n/a"
        return "Confluence backtest: $rate over $validSetups valid setups. $verdict"
    }
}

/**
 * Walk-forward test of the strategy library and the confluence engine together (sections 18-19). Every
 * strategy's own directional call is graded independently the moment its outcome is known - never using a
 * result the strategy could not have seen yet - and that record then calibrates its weight in the
 * confluence engine used for the NEXT prediction, exactly like live use.
 */
class ConfluenceBacktestEngine(
    private val strategies: List<Strategy> = defaultStrategies(),
    private val minCandlesForSignal: Int = 150,
    private val modelCandleCap: Int = 1500
) {
    fun run(candles: List<Candle>, expiryCandles: Int): ConfluenceBacktestReport {
        val ordered = candles.sortedBy { it.openTimeMs }.distinctBy { it.openTimeMs }
        val trackers = strategies.associate { it.name to StrategyPerformanceTracker() }

        data class PendingStrategy(val name: String, val direction: QuotexDecision)
        data class PendingOverall(val quality: SetupQuality, val direction: QuotexDecision)

        val pendingStrategies = HashMap<Int, List<PendingStrategy>>()
        val pendingOverall = HashMap<Int, PendingOverall>()

        var totalSetups = 0
        var validSetups = 0
        var wins = 0
        var losses = 0
        var winStreak = 0
        var lossStreak = 0
        var maxWinStreak = 0
        var maxLossStreak = 0
        val byQuality = HashMap<SetupQuality, Pair<Int, Int>>()

        fun resolveAt(i: Int) {
            val price = ordered[i].close
            pendingStrategies.remove(i)?.forEach { pending ->
                val startPrice = ordered[i - expiryCandles].close
                if (price == startPrice) return@forEach
                val higher = price > startPrice
                val hit = (pending.direction == QuotexDecision.CALL) == higher
                trackers[pending.name]?.record(hit)
            }
            pendingOverall.remove(i)?.let { pending ->
                val startPrice = ordered[i - expiryCandles].close
                if (price == startPrice) return@let
                val higher = price > startPrice
                val hit = (pending.direction == QuotexDecision.CALL) == higher
                val (count, w) = byQuality[pending.quality] ?: Pair(0, 0)
                byQuality[pending.quality] = Pair(count + 1, w + if (hit) 1 else 0)
                if (pending.quality == SetupQuality.SETUP_DETECTED || pending.quality == SetupQuality.HIGH_CONFLUENCE_SETUP) {
                    validSetups++
                    if (hit) {
                        wins++; winStreak++; lossStreak = 0
                        if (winStreak > maxWinStreak) maxWinStreak = winStreak
                    } else {
                        losses++; lossStreak++; winStreak = 0
                        if (lossStreak > maxLossStreak) maxLossStreak = lossStreak
                    }
                }
            }
        }

        var evaluated = 0
        for (i in ordered.indices) {
            if (i >= expiryCandles) resolveAt(i)
            if (i < minCandlesForSignal - 1) continue
            evaluated++
            if (i + expiryCandles >= ordered.size) continue // no future candle to resolve against - skip near the end

            val weights = trackers.mapValues { it.value.weight() }
            val series = PriceSeries.window(ordered, i + 1, modelCandleCap)
            val trend = MarketStructure.trendLabel(series)
            val structure = MarketStructure.structureLabel(MarketStructure.swings(series))
            val volatility = MarketStructure.volatilityLabel(series)
            val confluence = ConfluenceEngine(strategies, weights).evaluate(series, trend, structure, volatility)

            totalSetups += if (confluence.direction != QuotexDecision.WAIT) 1 else 0
            pendingOverall[i + expiryCandles] = PendingOverall(confluence.quality, confluence.direction)
            val leaningStrategies = confluence.strategyResults.filter { it.direction != QuotexDecision.WAIT }
                .map { PendingStrategy(it.strategyName, it.direction) }
            if (leaningStrategies.isNotEmpty()) pendingStrategies[i + expiryCandles] = leaningStrategies
        }
        // Any setups still pending near the end of the data simply have no future candle to resolve
        // against yet - they are left unresolved rather than guessed at, same as in live use.

        val perStrategy = strategies.map { s ->
            val t = trackers.getValue(s.name)
            StrategyStat(s.name, t.samples, t.hits, t.samples - t.hits, t.weight())
        }

        return ConfluenceBacktestReport(
            totalCandles = ordered.size, evaluatedCandles = evaluated, totalSetups = totalSetups,
            validSetups = validSetups, wins = wins, losses = losses,
            streaks = OutcomeStreak(maxWinStreak, maxLossStreak, if (winStreak > 0) winStreak else -lossStreak),
            byQuality = byQuality, perStrategy = perStrategy, verdict = verdictFor(validSetups, wins)
        )
    }

    private fun verdictFor(validSetups: Int, wins: Int): String {
        if (validSetups < 30) return "Not enough valid setups for a meaningful verdict (need 30+, have $validSetups)."
        val acc = wins.toDouble() / validSetups
        val z = (acc - 0.5) / sqrt(0.25 / validSetups)
        return when {
            z >= 2.0 -> "Valid setups hit ${Fmt.pct(acc, 1)}, above a coin flip (z=${Fmt.num(z)}). Confirm on fresh data before trusting it."
            z <= -2.0 -> "Valid setups hit only ${Fmt.pct(acc, 1)}, WORSE than a coin flip (z=${Fmt.num(z)})."
            else -> "No statistically significant edge yet (${Fmt.pct(acc, 1)}, z=${Fmt.num(z)}). Treat any setup as noise for now."
        }
    }
}
