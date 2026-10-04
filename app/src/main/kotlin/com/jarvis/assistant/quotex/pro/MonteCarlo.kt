package com.jarvis.assistant.quotex.pro

import java.util.Random
import kotlin.math.max

data class MonteCarloResult(
    val runs: Int,
    val trades: Int,
    val expectancyPerTrade: Double,
    val medianFinal: Double,
    val p5Final: Double,
    val medianMaxDrawdown: Double,
    val p95MaxDrawdown: Double,
    val ruinProbability: Double,
    val warnings: List<String>
)

/**
 * Randomised trade-order / bootstrap simulation. Input is per-trade P/L in stake units (win = +payout, loss = -1).
 * It shows how much of a backtest's equity curve was luck of ordering; it does NOT predict future results.
 */
object MonteCarlo {

    fun simulate(
        pnl: List<Double>,
        runs: Int = 2000,
        seed: Long = 42L,
        bootstrap: Boolean = false,
        ruinDrawdownStakes: Double = 10.0
    ): MonteCarloResult {
        val warnings = ArrayList<String>()
        if (pnl.isEmpty()) {
            return MonteCarloResult(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, listOf("No trades to simulate"))
        }
        if (pnl.size < 30) warnings += "Only ${pnl.size} trades: results are not statistically meaningful"
        val rnd = Random(seed)
        val n = pnl.size
        val finals = DoubleArray(runs)
        val dds = DoubleArray(runs)
        var ruined = 0
        val work = pnl.toDoubleArray()
        for (r in 0 until runs) {
            if (!bootstrap) {
                for (i in n - 1 downTo 1) {
                    val j = rnd.nextInt(i + 1)
                    val t = work[i]; work[i] = work[j]; work[j] = t
                }
            }
            var eq = 0.0
            var peak = 0.0
            var maxDd = 0.0
            for (i in 0 until n) {
                eq += if (bootstrap) pnl[rnd.nextInt(n)] else work[i]
                peak = max(peak, eq)
                maxDd = max(maxDd, peak - eq)
            }
            finals[r] = eq
            dds[r] = maxDd
            if (maxDd >= ruinDrawdownStakes) ruined++
        }
        finals.sort(); dds.sort()
        val expectancy = pnl.average()
        val ruinProb = ruined.toDouble() / runs
        if (expectancy <= 0.0) warnings += "Expectancy is not positive (%.3f stakes/trade)".format(expectancy)
        if (ruinProb > 0.10) warnings += "Drawdown of $ruinDrawdownStakes stakes reached in ${(ruinProb * 100).toInt()}% of simulations"
        if (finals[(runs * 0.05).toInt().coerceAtMost(runs - 1)] < 0.0) warnings += "5% of simulations end in a loss"
        return MonteCarloResult(
            runs, n, expectancy,
            finals[runs / 2], finals[(runs * 0.05).toInt().coerceAtMost(runs - 1)],
            dds[runs / 2], dds[(runs * 0.95).toInt().coerceAtMost(runs - 1)],
            ruinProb, warnings
        )
    }

    /** Break-even win rate for a given payout (0.85 -> 54.05%). */
    fun breakEvenWinRate(payout: Double): Double = 1.0 / (1.0 + payout)

    /** Expectancy per stake at win rates around [winRate]; shows how thin the edge is. */
    fun sensitivity(winRate: Double, payout: Double, stepsPp: List<Double> = listOf(-0.05, -0.025, 0.0, 0.025, 0.05)): Map<Double, Double> =
        stepsPp.associate { d ->
            val w = (winRate + d).coerceIn(0.0, 1.0)
            w to (w * payout - (1.0 - w))
        }
}

enum class Robustness { ROBUST, FRAGILE, INSUFFICIENT }

/** Parameter-neighbourhood check: an edge that only exists at one exact setting is overfit. */
object ParameterRobustness {
    /** [expectancyByParam]: expectancy (stakes/trade) for the chosen parameter value AND its neighbours. */
    fun assess(expectancyByParam: Map<String, Double>): Pair<Robustness, String> {
        if (expectancyByParam.size < 5) return Robustness.INSUFFICIENT to "Need at least 5 neighbouring parameter values"
        val positive = expectancyByParam.values.count { it > 0.0 }
        val share = positive.toDouble() / expectancyByParam.size
        return if (share >= 0.7) Robustness.ROBUST to "$positive of ${expectancyByParam.size} neighbouring settings are profitable"
        else Robustness.FRAGILE to "Only $positive of ${expectancyByParam.size} neighbouring settings are profitable - likely overfit"
    }
}
