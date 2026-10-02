package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision

/** Section 26. SIMULATION is the default: setups are recorded as paper setups and shown only as WATCH. */
enum class AgentMode { SIMULATION, PAPER_TRADING, BACKTEST, LIVE_ANALYSIS }

data class AgentSnapshot(
    val report: AgentReport,
    val lifecycle: LifecycleReading,
    val mode: AgentMode,
    val candleRemainingMs: Long,
    val candleMs: Long,
    val evidence: EdgeCheck
)

/**
 * Stateful wrapper used once per closed candle: validate -> analyse -> journal -> lifecycle -> verify earlier setups.
 * Analysis and bookkeeping only. There is deliberately no order, account or execution concept here.
 */
class AgentRuntime(
    private val config: AgentConfig = AgentConfig(),
    private val journal: QuotexJournal = QuotexJournal(),
    private val newsFilter: NewsRiskFilter = NewsRiskFilter(null),
    var mode: AgentMode = AgentMode.SIMULATION
) {
    private val analyzer = AgentAnalyzer(config, newsFilter)
    private val lifecycle = SignalLifecycle(validityMs = config.candleSeconds * 1000L * config.expiryCandles)
    private val candleMs = config.candleSeconds * 1000L

    fun journal(): QuotexJournal = journal

    /** Wins/losses of every resolved journal entry (real and paper) - the evidence the edge gate looks at. */
    fun evidence(): HistoricalEvidence {
        val resolved = journal.last(500).filter { it.outcome == JournalOutcome.WIN || it.outcome == JournalOutcome.LOSS }
        return HistoricalEvidence(resolved.size, resolved.count { it.outcome == JournalOutcome.WIN })
    }

    fun onCandleClosed(
        candles: List<Candle>,
        nowMs: Long,
        asset: String,
        weights: Map<String, Double> = emptyMap(),
        riskPausedReason: String? = null,
        ocrConfidence: Double? = null
    ): AgentSnapshot {
        resolvePending(candles)
        val evidence = evidence()
        val edge = EdgeTest.check(evidence, config.breakEven, config.edgeMinSamples, config.edgeZThreshold)
        val raw = analyzer.analyze(candles, nowMs, ocrConfidence, evidence, weights, riskPausedReason)

        // Paper tracking: a candidate that passed every gate except the edge gate is recorded so evidence can build up.
        val edgeBlocked = raw.checks.any { it.step == "Historical performance" && !it.passed }
        val candidate = raw.status == AgentStatus.SETUP_DETECTED || (edgeBlocked && raw.direction != QuotexDecision.WAIT)
        if (candidate && journal.pending().isEmpty() && candles.isNotEmpty()) {
            record(raw, candles.last(), asset, nowMs, shadow = raw.status != AgentStatus.SETUP_DETECTED)
        }

        val shown = if (raw.status == AgentStatus.SETUP_DETECTED && mode != AgentMode.LIVE_ANALYSIS) {
            raw.copy(
                status = AgentStatus.WATCH,
                reasons = listOf("${mode.name.replace('_', ' ')} MODE - setup recorded as a paper setup, not shown as live.") + raw.reasons
            )
        } else {
            raw
        }
        val reading = lifecycle.update(shown, nowMs)
        // An ended setup must not keep showing as SETUP DETECTED.
        val finalReport = if (shown.status == AgentStatus.SETUP_DETECTED &&
            (reading.state == LifecycleState.EXPIRED || reading.state == LifecycleState.INVALIDATED)
        ) {
            shown.copy(status = AgentStatus.WAIT, direction = QuotexDecision.WAIT, reasons = listOf(reading.reason ?: "Setup ended.") + shown.reasons)
        } else {
            shown
        }
        return AgentSnapshot(finalReport, reading, mode, CandleClock.remainingMs(nowMs, candleMs), candleMs, edge)
    }

    private fun record(r: AgentReport, last: Candle, asset: String, nowMs: Long, shadow: Boolean) {
        val f = if (r.conditionsTotal == 0) 0.0 else r.conditionsMet.toDouble() / r.conditionsTotal
        val bucket = when {
            f >= 0.85 -> "85%+"
            f >= 0.7 -> "70-85%"
            else -> "60-70%"
        }
        val features = "trend=${r.trend};structure=${r.structure};vol=${r.volatility};regime=${r.regime};data=${r.dataQuality};news=${r.newsRisk}"
        journal.record(
            JournalEntry(
                id = "$asset-${last.openTimeMs}", timestampMs = nowMs, asset = asset, timeframeSeconds = config.candleSeconds,
                regime = r.regime.name, strategies = r.strategies, direction = r.direction.name,
                quality = (if (shadow) "PAPER_" else "") + r.quality.name, confidenceBucket = bucket,
                conditionsMet = r.conditionsMet, conditionsTotal = r.conditionsTotal, featuresSummary = features,
                entryPrice = last.close, expiryMs = last.openTimeMs + config.expiryCandles * candleMs,
                strategyVersion = r.strategyVersion, analysisVersion = r.analysisVersion,
                indicatorSettings = r.indicatorSettings, session = r.session.name, reasonText = r.reasons.joinToString(" ")
            )
        )
    }

    /** Settles PENDING setups once the candle at their expiry exists. Settled entries are never edited again. */
    private fun resolvePending(candles: List<Candle>) {
        if (candles.isEmpty()) return
        for (p in journal.pending()) {
            val expiryCandle = candles.lastOrNull { it.openTimeMs == p.expiryMs } ?: continue
            val outcome = when {
                expiryCandle.close == p.entryPrice -> JournalOutcome.DRAW
                p.direction == QuotexDecision.CALL.name -> if (expiryCandle.close > p.entryPrice) JournalOutcome.WIN else JournalOutcome.LOSS
                else -> if (expiryCandle.close < p.entryPrice) JournalOutcome.WIN else JournalOutcome.LOSS
            }
            journal.resolve(p.id, outcome, expiryCandle.close)
        }
        // A pending setup whose expiry candle can never arrive (feed gap) is invalidated rather than left open forever.
        val newest = candles.last().openTimeMs
        for (p in journal.pending()) {
            if (newest > p.expiryMs + candleMs * 3) journal.resolve(p.id, JournalOutcome.INVALIDATED, null)
        }
    }
}
