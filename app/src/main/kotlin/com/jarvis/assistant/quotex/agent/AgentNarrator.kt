package com.jarvis.assistant.quotex.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun pct(x: Double?): String = if (x == null) "n/a" else "${(x * 1000).toInt() / 10.0}%"

/** Plain-text renderings for chat, voice and the dashboard. Reads only what the agent already computed. */
object AgentNarrator {

    fun current(s: AgentSnapshot?): String {
        if (s == null) return "The Quotex agent has not produced an analysis yet - keep collecting price history."
        val r = s.report
        val clock = "Candle closes in ${CandleClock.format(CandleClock.remainingMs(System.currentTimeMillis(), s.candleMs))}."
        return "${r.status.emoji} ${r.status.label} - ${r.headlineReason.ifEmpty { "conditions reviewed" }} $clock (${s.mode.name.replace('_', ' ')} mode)"
    }

    fun whyNoTrade(s: AgentSnapshot?): String {
        if (s == null) return "No analysis yet."
        val r = s.report
        if (r.status == AgentStatus.SETUP_DETECTED) return "A setup is currently detected, so this is not a no-trade situation. " + r.headlineReason
        val failed = r.checks.filter { !it.passed }.joinToString("; ") { "${it.step}: ${it.detail}" }
        return "${r.status.label}. ${r.headlineReason}" + if (failed.isNotEmpty()) "\nFailed checks: $failed" else ""
    }

    fun explain(s: AgentSnapshot?): String = if (s == null) "There is nothing to explain yet." else ExplanationEngine.explain(s.report)

    fun confluence(s: AgentSnapshot?): String = if (s == null) "No confluence data yet." else ExplanationEngine.confluenceBreakdown(s.report)

    fun speech(s: AgentSnapshot?): String = if (s == null) "No analysis yet." else ExplanationEngine.speech(s.report)

    fun journalList(entries: List<JournalEntry>): String {
        if (entries.isEmpty()) return "The journal is empty so far."
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        return entries.joinToString("\n") {
            "• ${fmt.format(Date(it.timestampMs))} ${it.asset} ${it.direction} [${it.regime}] ${it.outcome.name} (${it.conditionsMet}/${it.conditionsTotal}, ${it.quality})"
        }
    }

    fun summary(label: String, j: JournalSummary): String =
        "$label: ${j.total} setups (${j.wins}W / ${j.losses}L / ${j.draws} draw, ${j.pending} pending), hit rate ${pct(j.hitRate)} " +
            "over ${j.resolved} resolved. Paper setups are included. Small samples prove nothing."

    fun whyFailed(journal: QuotexJournal): String {
        val failed = journal.last(50).firstOrNull { it.outcome == JournalOutcome.LOSS }
            ?: return "No losing setup is recorded in the journal."
        return journal.whyFailed(failed)
    }
}

/** Text form of a walk-forward report. Poor results are shown exactly like good ones. */
fun AgentBacktestReport.toText(): String {
    fun seg(s: SegmentReport): String =
        "${s.name}: ${s.setups} setups, ${s.wins}W/${s.losses}L/${s.draws}D, hit ${pct(s.hitRate)}, " +
            "z=${s.zVsBreakEven?.let { (it * 100).toInt() / 100.0 } ?: "n/a"}, streaks +${s.maxWinStreak}/-${s.maxLossStreak}"
    val strategies = outOfSample.byStrategy.entries.joinToString("\n") { (k, v) -> "  • $k: ${pct(v.accuracy)} over ${v.samples}" }
    return buildString {
        append(summary).append("\n")
        append(seg(train)).append("\n").append(seg(validation)).append("\n").append(seg(outOfSample)).append("\n")
        if (strategies.isNotEmpty()) append("Out-of-sample by strategy:\n").append(strategies).append("\n")
        append(calibrationNote)
    }
}
