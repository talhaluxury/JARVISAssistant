package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.trading.QuotexDecision

/** Sections 15, 28, 29, 30: turns an [AgentReport] into text. Wording only - no analysis happens here. */
object ExplanationEngine {

    private const val DISCLAIMER = "This is an analytical setup, not a guaranteed outcome."

    private fun nice(s: String): String = s.lowercase().replace('_', ' ')

    fun explain(r: AgentReport): String {
        val sb = StringBuilder("JARVIS analysis:\n")
        sb.append("Data quality is ${nice(r.dataQuality.name)}.\n")
        if (r.status == AgentStatus.DATA_UNCERTAIN) {
            sb.append("Result: ${r.status.label}.\n")
            sb.append("Reason: ${r.headlineReason}\n")
            return sb.toString()
        }
        sb.append("Market regime: ${nice(r.regime.name)}; trend ${nice(r.trend.name)}; structure ${nice(r.structure.name)}.\n")
        sb.append("Volatility is ${nice(r.volatility.name)}. Session: ${nice(r.session.name)}.\n")
        if (r.newsRisk != NewsRisk.UNAVAILABLE) sb.append("News risk: ${r.newsRisk.name}.\n")
        val conf = r.confluence
        if (conf != null && r.direction != QuotexDecision.WAIT) {
            val met = conf.strategyResults.filter { it.direction == r.direction }
                .flatMap { s -> s.conditions.filter { it.satisfied }.map { it.name } }.distinct().take(6)
            val unmet = conf.strategyResults.filter { it.direction == r.direction }
                .flatMap { s -> s.conditions.filter { !it.satisfied }.map { it.name } }.distinct().take(3)
            if (met.isNotEmpty()) sb.append("Supporting: ${met.joinToString("; ")}.\n")
            if (unmet.isNotEmpty()) sb.append("Not yet met: ${unmet.joinToString("; ")}.\n")
        }
        sb.append("\nResult: ${r.status.label}")
        if (r.direction != QuotexDecision.WAIT && r.status != AgentStatus.WAIT) sb.append(" (${r.direction.name})")
        sb.append(".\n")
        if (r.conditionsTotal > 0) sb.append("Reasoning: ${r.conditionsMet} of ${r.conditionsTotal} configured conditions agree.\n")
        for (reason in r.reasons) sb.append("Note: $reason\n")
        sb.append("\n$DISCLAIMER")
        return sb.toString()
    }

    /** Section 15 card. */
    fun card(r: AgentReport, asset: String, candleSeconds: Int, signalAgeSeconds: Long, invalidation: String): String {
        val sb = StringBuilder()
        sb.append("--------------------------------\nJ.A.R.V.I.S.\nTRADING INTELLIGENCE\n--------------------------------\n\n")
        sb.append("ASSET:\n$asset\n\nTIMEFRAME:\n${CandleClock.label(candleSeconds)}\n\n")
        sb.append("MARKET:\n${trendWord(r)}\n\n")
        if (r.status == AgentStatus.SETUP_DETECTED || r.status == AgentStatus.WATCH) {
            sb.append("SETUP:\n${r.strategies.joinToString(" + ").uppercase().ifEmpty { "-" }}\n\n")
        }
        sb.append("STATUS:\n${r.status.label}\n\n")
        if (r.conditionsTotal > 0) sb.append("CONFIRMATION:\n${r.conditionsMet} / ${r.conditionsTotal} CONDITIONS\n\n")
        sb.append("DATA QUALITY:\n${r.dataQuality.name}\n\n")
        if (r.status == AgentStatus.SETUP_DETECTED) sb.append("SIGNAL AGE:\n$signalAgeSeconds seconds\n\nINVALIDATION:\n$invalidation\n")
        else sb.append("REASON:\n${r.headlineReason}\n")
        sb.append("\n--------------------------------")
        return sb.toString()
    }

    private fun trendWord(r: AgentReport): String = when (r.regime) {
        MarketRegime.STRONG_UPTREND, MarketRegime.WEAK_UPTREND, MarketRegime.BREAKOUT -> "BULLISH"
        MarketRegime.STRONG_DOWNTREND, MarketRegime.WEAK_DOWNTREND, MarketRegime.BREAKDOWN -> "BEARISH"
        MarketRegime.RANGE -> "RANGE"
        MarketRegime.TRANSITION -> "TRANSITION"
        MarketRegime.UNSTABLE -> "UNCLEAR"
    }

    /** Short, actionable voice line (section 29). */
    fun speech(r: AgentReport): String = when (r.status) {
        AgentStatus.SETUP_DETECTED -> "Setup detected, ${r.direction.name.lowercase()} bias, ${r.conditionsMet} of ${r.conditionsTotal} conditions. Not a guarantee."
        AgentStatus.WATCH -> "Watching. A setup is forming but is not confirmed."
        AgentStatus.WAIT -> "Wait. ${firstSentence(r.headlineReason)}"
        AgentStatus.NO_TRADE -> "No trade. ${firstSentence(r.headlineReason)}"
        AgentStatus.DATA_UNCERTAIN -> "Data uncertain. No trade."
    }

    /** Section 30 overlay body. */
    fun overlayLines(r: AgentReport, asset: String, candleSeconds: Int): List<String> = listOf(
        "$asset  ${CandleClock.label(candleSeconds)}",
        "TREND: ${trendWord(r)}",
        "STRUCTURE: ${nice(r.structure.name).uppercase()}",
        "VOLATILITY: ${r.volatility.name}",
        "CONFLUENCE: ${r.conditionsMet} / ${r.conditionsTotal}",
        "STATUS: ${r.status.emoji} ${r.status.label}"
    )

    fun confluenceBreakdown(r: AgentReport): String {
        val conf = r.confluence ?: return "No confluence data: ${r.headlineReason}"
        return conf.strategyResults.filter { it.direction != QuotexDecision.WAIT }.joinToString("\n") {
            "${it.strategyName}: ${it.direction.name} ${it.satisfiedCount}/${it.totalCount}"
        }.ifEmpty { "No strategy currently sees a setup." }
    }

    private fun firstSentence(s: String): String {
        val idx = s.indexOf('.')
        return if (idx in 1 until s.length) s.substring(0, idx + 1) else s
    }
}
