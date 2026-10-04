package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.quotex.agent.AgentReport
import com.jarvis.assistant.quotex.agent.AgentSnapshot
import com.jarvis.assistant.quotex.agent.AgentStatus
import com.jarvis.assistant.quotex.agent.JournalEntry
import com.jarvis.assistant.quotex.agent.JournalOutcome
import com.jarvis.assistant.quotex.agent.NewsRisk
import com.jarvis.assistant.trading.QuotexDecision

/** Builds the explainable-signal text, reasons, structure and performance views from an [AgentSnapshot] only. */
object ProNarrator {

    fun toInput(r: AgentReport): ExplanationInput {
        val confirmations = r.checks.filter { it.passed && it.step in CONFIRM_STEPS }.map { "${it.step}: ${it.detail}" }
        val warnings = ArrayList<String>(r.warnings)
        r.checks.filter { !it.passed }.forEach { warnings.add("${it.step}: ${it.detail}") }
        if (r.newsRisk == NewsRisk.UNAVAILABLE) warnings.add("NEWS DATA UNAVAILABLE - news risk is unknown, not absent")
        val risk = when {
            r.newsRisk == NewsRisk.HIGH -> "HIGH (news)"
            r.volatility.name == "EXTREME" -> "HIGH (volatility)"
            r.checks.any { it.step == "Risk" && !it.passed } -> "PAUSED"
            warnings.size >= 3 -> "ELEVATED"
            else -> "MODERATE"
        }
        val incomplete = r.status == AgentStatus.DATA_UNCERTAIN || r.confluence == null || r.newsRisk == NewsRisk.UNAVAILABLE
        val decision = when {
            r.status == AgentStatus.SETUP_DETECTED && r.direction != QuotexDecision.WAIT -> r.direction.name
            r.status == AgentStatus.NO_TRADE || r.status == AgentStatus.DATA_UNCERTAIN -> "NO SIGNAL"
            else -> "WAIT"
        }
        return ExplanationInput(
            decision = decision, setupScore = r.setupScore, regime = r.regime.name.replace('_', ' '),
            entryQuality = r.entry?.let { "${it.quality} (${it.reason})" },
            dataQuality = "${r.dataQuality} - ${r.dataSummary}", risk = risk,
            confirmations = confirmations, warnings = warnings, incomplete = incomplete
        )
    }

    private val CONFIRM_STEPS = setOf("Data quality", "Regime", "Conflicts", "Multi-timeframe", "Structure events", "Level", "Timing", "Fake breakout", "Entry quality", "Confirmation", "Historical performance")

    fun signal(s: AgentSnapshot?): String =
        if (s == null) "No analysis yet. Start the monitor first." else ExplanationBuilder.build(toInput(s.report))

    fun reasons(s: AgentSnapshot?): String {
        val r = s?.report ?: return "No analysis yet."
        val sb = StringBuilder("Reasons:\n")
        r.reasons.forEach { sb.append("\u2022 ").append(it).append('\n') }
        r.checks.forEach { sb.append(if (it.passed) "\u2713 " else "\u2717 ").append(it.step).append(": ").append(it.detail).append('\n') }
        return sb.toString().trim()
    }

    fun structure(s: AgentSnapshot?): String {
        val r = s?.report ?: return "No analysis yet."
        val sb = StringBuilder("Market structure\nTrend: ${r.trend}\nStructure: ${r.structure}\nVolatility: ${r.volatility}\nRegime: ${r.regime}\n")
        r.fakeBreakout?.takeIf { it.kind != FakeBreakKind.NONE }?.let { sb.append("Fake break: ${it.kind} (quality ${it.quality}) - ${it.detail}\n") }
        r.entry?.let { sb.append("Entry: ${it.quality} - ${it.reason}\n") }
        return sb.toString().trim()
    }

    /** Honest historical view: no percentages below [minSample] resolved setups. */
    fun performance(entries: List<JournalEntry>, minSample: Int = 30): String {
        val done = entries.filter { it.outcome == JournalOutcome.WIN || it.outcome == JournalOutcome.LOSS }
        if (done.isEmpty()) return "No resolved setups yet, so there is no historical performance to show."
        fun lines(title: String, key: (JournalEntry) -> String): String =
            title + "\n" + PerformanceStats.groupLines(done, key) { it.outcome == JournalOutcome.WIN }.joinToString("\n") { "  " + it.display(minSample) }
        val pnl = done.map { if (it.outcome == JournalOutcome.WIN) 0.85 else -1.0 }
        return listOf(
            StatLine("All", done.count { it.outcome == JournalOutcome.WIN }, done.count { it.outcome == JournalOutcome.LOSS }).display(minSample),
            lines("By regime", { it.regime }), lines("By session", { it.session }),
            lines("By timeframe", { "${it.timeframeSeconds}s" }),
            "Max drawdown (0.85 payout assumed): %.1f stakes".format(PerformanceStats.maxDrawdown(pnl)),
            "Past results do not predict future ones."
        ).joinToString("\n\n")
    }

    fun monteCarlo(entries: List<JournalEntry>, payout: Double = 0.85): String {
        val pnl = entries.filter { it.outcome == JournalOutcome.WIN || it.outcome == JournalOutcome.LOSS }
            .map { if (it.outcome == JournalOutcome.WIN) payout else -1.0 }
        val r = MonteCarlo.simulate(pnl)
        if (r.trades == 0) return "No resolved setups to simulate."
        return "Monte Carlo (${r.runs} shuffles of ${r.trades} trades)\n" +
            "Expectancy %.3f stakes/trade (break-even win rate %.1f%%)\n".format(r.expectancyPerTrade, MonteCarlo.breakEvenWinRate(payout) * 100) +
            "Median drawdown %.1f, 95th percentile %.1f stakes\n".format(r.medianMaxDrawdown, r.p95MaxDrawdown) +
            r.warnings.joinToString("\n") { "\u26A0 $it" }
    }

    fun lab(specs: List<RuleSpec>, stat: (String) -> PaperStat): String {
        if (specs.isEmpty()) return "Strategy Lab is empty. Open Pro Analyzer > Strategy Lab to create a rule."
        return "Strategy Lab\n" + specs.joinToString("\n") {
            val st = stat(it.id)
            "\u2022 ${it.name} [${it.status}]: ${it.describe()}; paper ${st.trades} trades" +
                if (st.trades >= 30) ", expectancy %.3f".format(st.expectancy) else " (too few to judge)"
        }
    }

    fun news(r: AgentReport?): String {
        val risk = r?.newsRisk ?: return "No analysis yet."
        return when (risk) {
            NewsRisk.UNAVAILABLE -> "NEWS DATA UNAVAILABLE. No calendar is configured, so news risk is unknown - not absent. Add events in Pro Analyzer > Settings."
            NewsRisk.HIGH -> "HIGH NEWS RISK - a high-impact event is near. WAIT / caution."
            NewsRisk.MEDIUM -> "Medium news risk: an event is within the next hour."
            NewsRisk.LOW -> "Low news risk according to the calendar you maintain."
        }
    }
}
