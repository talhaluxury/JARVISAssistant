package com.jarvis.assistant.quotex.pro

/**
 * SETUP SCORE 0-100: how strong and well-aligned the evidence is. It is NOT a probability of winning and must never
 * be shown as one. Evidence groups are fixed-weight so correlated inputs cannot be counted twice.
 */
data class ScoreInput(
    val conditionsFraction: Double,   // independent conditions satisfied / total
    val strategyAgreement: Double,    // agreeing strategies / strategies that took a side
    val mtfAgrees: Boolean?,          // null = not enough history
    val dataQualityScore: Int,        // 0..100
    val patternQuality: Int?,         // best supporting price-action quality, null = none
    val warnings: Int,
    val entryQuality: EntryQuality?
)

object SetupScorer {
    const val W_CONDITIONS = 40.0
    const val W_STRATEGY = 20.0
    const val W_MTF = 15.0
    const val W_DATA = 15.0
    const val W_PATTERN = 10.0

    fun score(i: ScoreInput): Int {
        var s = W_CONDITIONS * i.conditionsFraction.coerceIn(0.0, 1.0) +
            W_STRATEGY * i.strategyAgreement.coerceIn(0.0, 1.0) +
            W_DATA * (i.dataQualityScore.coerceIn(0, 100) / 100.0) +
            W_PATTERN * ((i.patternQuality ?: 0).coerceIn(0, 100) / 100.0)
        s += when (i.mtfAgrees) { true -> W_MTF; null -> W_MTF * 0.5; false -> 0.0 }
        s -= i.warnings * 4.0
        // Bad timing caps the score so a LATE/INVALID entry can never look "strong".
        val cap = when (i.entryQuality) {
            EntryQuality.INVALID -> 20.0
            EntryQuality.LATE -> 59.0
            EntryQuality.EARLY -> 69.0
            else -> 100.0
        }
        return minOf(s, cap).coerceIn(0.0, 100.0).toInt()
    }

    fun dataQualityScore(name: String): Int = when (name) {
        "EXCELLENT" -> 96
        "GOOD" -> 82
        "FAIR" -> 62
        else -> 30
    }
}

/** Plain-language explanation built ONLY from structured fields - it can never invent a value. */
data class ExplanationInput(
    val decision: String,
    val setupScore: Int?,
    val regime: String,
    val entryQuality: String?,
    val dataQuality: String,
    val risk: String,
    val confirmations: List<String>,
    val warnings: List<String>,
    val incomplete: Boolean
)

object ExplanationBuilder {
    fun build(e: ExplanationInput): String {
        val sb = StringBuilder()
        sb.append("SIGNAL\n").append(e.decision).append("\n\n")
        sb.append("SETUP SCORE (strength, not a win probability)\n")
            .append(e.setupScore?.let { "$it/100" } ?: "n/a").append("\n\n")
        sb.append("MARKET REGIME\n").append(e.regime).append("\n\n")
        sb.append("CONFIRMATIONS\n")
        if (e.confirmations.isEmpty()) sb.append("- none\n") else e.confirmations.forEach { sb.append("\u2713 ").append(it).append('\n') }
        sb.append("\nWARNINGS\n")
        if (e.warnings.isEmpty()) sb.append("- none\n") else e.warnings.forEach { sb.append("\u2022 ").append(it).append('\n') }
        sb.append("\nENTRY QUALITY\n").append(e.entryQuality ?: "n/a")
        sb.append("\n\nDATA QUALITY\n").append(e.dataQuality)
        sb.append("\n\nRISK\n").append(e.risk)
        sb.append("\n\nDECISION\n").append(e.decision)
        if (e.incomplete) sb.append("\n\nAnalysis is incomplete: some inputs were unavailable, so treat this as WAIT-leaning.")
        sb.append("\n\nThis is a statistical setup read, not a prediction or a guarantee. You decide manually.")
        return sb.toString()
    }
}

/** Win/loss statistics that refuse to look meaningful on tiny samples. */
data class StatLine(val label: String, val wins: Int, val losses: Int) {
    val n: Int get() = wins + losses
    fun display(minSample: Int = 30): String =
        if (n < minSample) "$label: $wins W / $losses L (sample $n < $minSample - not meaningful)"
        else "$label: ${"%.1f".format(wins * 100.0 / n)}% over $n"
}

object PerformanceStats {
    fun maxDrawdown(pnl: List<Double>): Double {
        var eq = 0.0; var peak = 0.0; var dd = 0.0
        for (p in pnl) { eq += p; if (eq > peak) peak = eq; if (peak - eq > dd) dd = peak - eq }
        return dd
    }

    fun <T> groupLines(items: List<T>, key: (T) -> String, win: (T) -> Boolean?): List<StatLine> =
        items.groupBy(key).map { (k, v) ->
            val res = v.mapNotNull(win)
            StatLine(k, res.count { it }, res.count { !it })
        }.sortedByDescending { it.n }
}
