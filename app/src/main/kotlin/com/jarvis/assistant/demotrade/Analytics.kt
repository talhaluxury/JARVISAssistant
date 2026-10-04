package com.jarvis.assistant.demotrade

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BucketStat(val key: String, val trades: Int, val wins: Int, val losses: Int, val ties: Int, val pnl: Double) {
    val decided: Int get() = wins + losses
    val winRate: Double? get() = if (decided == 0) null else wins.toDouble() / decided
}

data class TradeStats(
    val total: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val voids: Int = 0,
    val winRate: Double? = null,
    val totalPnl: Double = 0.0,
    val avgWin: Double = 0.0,
    val avgLoss: Double = 0.0,
    val avgPnl: Double = 0.0,
    val expectancy: Double = 0.0,
    val profitFactor: Double? = null,
    val maxDrawdown: Double = 0.0,
    val maxDrawdownPct: Double = 0.0,
    val longestWinStreak: Int = 0,
    val longestLossStreak: Int = 0,
    val byStrategy: List<BucketStat> = emptyList(),
    val byRegime: List<BucketStat> = emptyList(),
    val byHour: List<BucketStat> = emptyList(),
    val byConfidence: List<BucketStat> = emptyList(),
    val bestStrategy: String? = null,
    val worstStrategy: String? = null,
    val bestRegime: String? = null,
    val worstRegime: String? = null,
    val balanceCurve: List<Double> = emptyList(),
    val pnlCurve: List<Double> = emptyList(),
    val breakEvenWinRate: Double = 0.5555
)

object PerformanceAnalyzer {
    const val MIN_SAMPLES_FOR_RANKING = 5

    fun confidenceBucket(c: Int): String = when {
        c < 70 -> "<70"
        c < 75 -> "70-74"
        c < 85 -> "75-84"
        else -> "85-100"
    }

    private fun buckets(keyed: List<Pair<String, PaperTrade>>): List<BucketStat> =
        keyed.groupBy({ it.first }, { it.second }).map { (key, list) ->
            BucketStat(
                key = key,
                trades = list.size,
                wins = list.count { it.result == TradeResult.WIN },
                losses = list.count { it.result == TradeResult.LOSS || (it.result == TradeResult.TIE && it.tieLoses) },
                ties = list.count { it.result == TradeResult.TIE && !it.tieLoses },
                pnl = round2(list.sumOf { it.pnl ?: 0.0 })
            )
        }.sortedBy { it.key }

    private fun ranked(b: List<BucketStat>): List<BucketStat> =
        b.filter { it.decided >= MIN_SAMPLES_FOR_RANKING && it.winRate != null }
            .sortedWith(compareByDescending<BucketStat> { it.winRate ?: 0.0 }.thenByDescending { it.pnl })

    /** [trades] may contain open trades; they are ignored. Order does not matter, it is sorted by close time. */
    fun analyze(trades: List<PaperTrade>, startBalance: Double, payoutPercent: Double): TradeStats {
        val closed = trades.filter { it.result != null }.sortedBy { it.closedAtMs ?: it.openedAtMs }
        val breakEven = 1.0 / (1.0 + payoutPercent / 100.0)
        if (closed.isEmpty()) return TradeStats(balanceCurve = listOf(startBalance), pnlCurve = listOf(0.0), breakEvenWinRate = breakEven)

        val wins = closed.count { it.result == TradeResult.WIN }
        val ties = closed.count { it.result == TradeResult.TIE }
        val voids = closed.count { it.result == TradeResult.VOID }
        val losses = closed.count { it.result == TradeResult.LOSS }
        val pnls = closed.map { it.pnl ?: 0.0 }
        val grossWin = pnls.filter { it > 0.0 }.sum()
        val grossLoss = -pnls.filter { it < 0.0 }.sum()
        val winCount = pnls.count { it > 0.0 }
        val lossCount = pnls.count { it < 0.0 }
        val decided = wins + losses
        val counted = closed.count { it.result != TradeResult.VOID }

        var bal = startBalance
        var peak = startBalance
        var maxDd = 0.0
        var maxDdPct = 0.0
        val balances = ArrayList<Double>()
        val cumulative = ArrayList<Double>()
        balances.add(startBalance)
        cumulative.add(0.0)
        var running = 0.0
        var winStreak = 0
        var lossStreak = 0
        var bestWin = 0
        var bestLoss = 0
        for (t in closed) {
            val p = t.pnl ?: 0.0
            bal += p
            running += p
            balances.add(round2(bal))
            cumulative.add(round2(running))
            peak = max(peak, bal)
            val dd = peak - bal
            maxDd = max(maxDd, dd)
            if (peak > 0.0) maxDdPct = max(maxDdPct, dd / peak * 100.0)
            when (t.result) {
                TradeResult.WIN -> { winStreak++; lossStreak = 0; bestWin = max(bestWin, winStreak) }
                TradeResult.LOSS -> { lossStreak++; winStreak = 0; bestLoss = max(bestLoss, lossStreak) }
                else -> Unit
            }
        }

        val byStrategy = buckets(closed.flatMap { t -> t.strategies.map { it to t } })
        val byRegime = buckets(closed.map { it.regime.name to it })
        val byHour = buckets(closed.map { String.format(java.util.Locale.US, "%02d:00", hourOf(it.openedAtMs)) to it })
        val byConf = buckets(closed.map { confidenceBucket(it.confidence) to it })
        val rs = ranked(byStrategy)
        val rr = ranked(byRegime)

        return TradeStats(
            total = closed.size,
            wins = wins,
            losses = losses,
            ties = ties,
            voids = voids,
            winRate = if (decided == 0) null else wins.toDouble() / decided,
            totalPnl = round2(pnls.sum()),
            avgWin = if (winCount == 0) 0.0 else round2(grossWin / winCount),
            avgLoss = if (lossCount == 0) 0.0 else round2(-grossLoss / lossCount),
            avgPnl = if (counted == 0) 0.0 else round2(pnls.sum() / counted),
            expectancy = if (counted == 0) 0.0 else round2(pnls.sum() / counted),
            profitFactor = if (grossLoss > 0.0) grossWin / grossLoss else null,
            maxDrawdown = round2(maxDd),
            maxDrawdownPct = maxDdPct,
            longestWinStreak = bestWin,
            longestLossStreak = bestLoss,
            byStrategy = byStrategy,
            byRegime = byRegime,
            byHour = byHour,
            byConfidence = byConf,
            bestStrategy = rs.firstOrNull()?.key,
            worstStrategy = if (rs.size >= 2) rs.last().key else null,
            bestRegime = rr.firstOrNull()?.key,
            worstRegime = if (rr.size >= 2) rr.last().key else null,
            balanceCurve = balances,
            pnlCurve = cumulative,
            breakEvenWinRate = breakEven
        )
    }
}

/**
 * Analytics-driven filter. It can only make the engine MORE selective, only within the limits set in Settings
 * (max confidence bump, max blocked regimes/hours), and it needs enough closed trades before it says anything.
 * It never rewrites strategy logic. Switch it off in Settings at any time.
 */
object AdaptiveFilter {
    private const val MIN_BUCKET_TRADES = 15

    fun advise(history: List<PaperTrade>, s: DemoSettings): AdaptiveAdvice {
        if (!s.adaptiveEnabled) return AdaptiveAdvice.NONE
        val closed = history.filter { it.result == TradeResult.WIN || it.result == TradeResult.LOSS }
        if (closed.size < s.adaptiveMinTrades) {
            return AdaptiveAdvice(notes = listOf("Adaptive layer waiting for data: ${closed.size}/${s.adaptiveMinTrades} decided trades"))
        }
        val be = s.breakEvenWinRate
        val notes = ArrayList<String>()

        var lo = s.minConfidence
        var bump = 0
        for (edge in listOf(75, 85, 101)) {
            if (edge <= lo) continue
            val b = closed.filter { it.confidence >= lo && it.confidence < edge }
            val wr = if (b.isEmpty()) 1.0 else b.count { it.result == TradeResult.WIN }.toDouble() / b.size
            if (b.size >= MIN_BUCKET_TRADES && wr < be - 0.03) {
                notes.add("Confidence $lo-${edge - 1}: ${pct(wr)} win rate over ${b.size} trades, below break-even ${pct(be)}")
                lo = edge
                bump = lo - s.minConfidence
            } else break
        }
        bump = min(bump, s.adaptiveMaxBump)
        bump = min(bump, 100 - s.minConfidence)

        fun weak(group: Map<String, List<PaperTrade>>, margin: Double): List<Pair<String, Double>> =
            group.mapNotNull { (k, v) ->
                val wr = v.count { it.result == TradeResult.WIN }.toDouble() / v.size
                if (v.size >= MIN_BUCKET_TRADES && wr < be - margin) Pair(k, wr) else null
            }.sortedBy { it.second }

        val badRegimes = weak(closed.groupBy { it.regime.name }, 0.08).take(2)
        badRegimes.forEach { notes.add("Regime ${it.first}: ${pct(it.second)} win rate, blocked") }
        val badHours = weak(closed.groupBy { hourOf(it.openedAtMs).toString() }, 0.08).take(3)
        badHours.forEach { notes.add("Hour ${it.first}:00: ${pct(it.second)} win rate, blocked") }

        return AdaptiveAdvice(
            minConfidenceBump = bump,
            blockedRegimes = badRegimes.map { Regime.valueOf(it.first) }.toSet(),
            blockedHours = badHours.map { it.first.toInt() }.toSet(),
            notes = notes
        )
    }

    private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.0f%%", v * 100.0)
}

/** Short, hedged explanations. It never claims certainty and never says a setup "works". */
object TradeJournal {
    private fun num(v: Double): String = String.format(java.util.Locale.US, "%.5f", v)

    fun entryNote(t: PaperTrade): String =
        "Opened ${t.direction} at ${num(t.entryPrice)} (model confidence ${t.confidence}/100, regime ${t.regime.name}). " +
            "Strategies: ${t.strategies.joinToString().ifEmpty { "none" }}. Reasons: ${t.reasons.take(3).joinToString("; ")}."

    fun explain(t: PaperTrade): String {
        val result = t.result ?: return ""
        val exit = t.exitPrice
        val hedge = " (One result says little: model confidence is not a win probability.)"
        return when (result) {
            TradeResult.WIN -> {
                val moved = if (exit != null) abs(exit - t.entryPrice) else 0.0
                "Trade won: price finished ${num(moved)} " + (if (t.direction == Dir.CALL) "above" else "below") +
                    " the entry. ${t.strategies.size} strateg${if (t.strategies.size == 1) "y" else "ies"} agreed" +
                    (if (t.mtfNote.contains("higher") && !t.mtfNote.contains("not enough")) " and the timeframes were lined up" else "") + "." + hedge
            }
            TradeResult.LOSS -> {
                val atr = t.atrAtEntry
                val reason = when {
                    atr > 0.0 && t.maxAdverse > 1.5 * atr ->
                        "volatility expanded against the position soon after entry (worst move about ${String.format(java.util.Locale.US, "%.1f", t.maxAdverse / atr)}x ATR)"
                    atr > 0.0 && t.maxFavorable > 0.5 * atr ->
                        "price first moved in favour, then reversed before expiry"
                    t.regime == Regime.RANGE || t.regime == Regime.BREAKOUT ->
                        "the ${t.regime.name} regime is less reliable and price moved the other way"
                    t.confidence < 75 ->
                        "model confidence was only ${t.confidence}/100, close to the minimum"
                    else -> "price simply moved against the setup; some losses are normal for any model"
                }
                "Trade lost despite the confirmations: $reason.$hedge"
            }
            TradeResult.TIE -> "Price finished exactly at the entry price, so the trade was a tie."
            TradeResult.VOID -> "Trade voided and refunded: a valid price was not available at expiry, so no result was invented."
        }
    }
}
