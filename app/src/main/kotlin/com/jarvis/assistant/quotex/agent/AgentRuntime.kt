package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.pro.LabStatus
import com.jarvis.assistant.quotex.pro.MemoryAction
import com.jarvis.assistant.quotex.pro.MemoryOutcome
import com.jarvis.assistant.quotex.pro.SignalMemory
import com.jarvis.assistant.quotex.pro.StrategyRule
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
    private val memory = SignalMemory()

    /** Recent-setup memory (repeat failures, duplicates, CALL/PUT flipping). */
    fun signalMemory(): SignalMemory = memory

    fun journal(): QuotexJournal = journal

    private companion object { const val LIVE_VETO_SAMPLES = 30 }

    /** Untouched out-of-sample record of the last walk-forward backtest (sections 18-20); null until one has run. */
    @Volatile private var backtestEvidence: HistoricalEvidence? = null

    /** Feeds a finished walk-forward report to the edge gate. Only the OUT-OF-SAMPLE segment counts, never train/validation. */
    fun setBacktest(report: AgentBacktestReport?) {
        backtestEvidence = report?.outOfSample?.let { HistoricalEvidence(it.wins + it.losses, it.wins) }
    }

    fun hasBacktestEvidence(): Boolean = backtestEvidence != null

    /** Wins/losses of every resolved journal entry (real and paper): what live running has actually produced. */
    fun liveEvidence(): HistoricalEvidence {
        val resolved = journal.last(500).filter { it.outcome == JournalOutcome.WIN || it.outcome == JournalOutcome.LOSS }
        return HistoricalEvidence(resolved.size, resolved.count { it.outcome == JournalOutcome.WIN })
    }

    /**
     * Evidence the edge gate looks at: the walk-forward out-of-sample record when a backtest has run, otherwise the
     * live record. They are never pooled (the backtest replays the same candles the live journal saw).
     */
    fun evidence(): HistoricalEvidence = backtestEvidence ?: liveEvidence()

    /** Live results clearly below break-even veto the backtest's verdict (strategy degradation, section 35). */
    private fun liveDegraded(): Boolean {
        val live = liveEvidence()
        val acc = live.accuracy ?: return false
        return backtestEvidence != null && live.samples >= LIVE_VETO_SAMPLES && acc < config.breakEven
    }

    fun onCandleClosed(
        candles: List<Candle>,
        nowMs: Long,
        asset: String,
        weights: Map<String, Double> = emptyMap(),
        riskPausedReason: String? = null,
        ocrConfidence: Double? = null,
        labRules: List<StrategyRule> = emptyList()
    ): AgentSnapshot {
        resolvePending(candles)
        val evidence = evidence()
        val rawEdge = EdgeTest.check(evidence, config.breakEven, config.edgeMinSamples, config.edgeZThreshold)
        val edge = rawEdge.copy(summary = (if (backtestEvidence != null) "Walk-forward out-of-sample: " else "Live journal: ") + rawEdge.summary)
        var raw = analyzer.analyze(candles, nowMs, ocrConfidence, evidence, weights, riskPausedReason)
        if (raw.status == AgentStatus.SETUP_DETECTED && config.requireVerifiedEdge && liveDegraded()) {
            val live = liveEvidence()
            raw = raw.copy(
                status = AgentStatus.WATCH,
                reasons = listOf("Live results (${live.hits}/${live.samples}) are below break-even, which overrides the backtest. No setup shown until they recover.") + raw.reasons
            )
        }

        // Strategy Lab rules that passed validation (ENABLED) act only as extra evidence: they can never create a setup.
        if (labRules.isNotEmpty() && raw.direction != QuotexDecision.WAIT && candles.isNotEmpty()) {
            try {
                val ser = PriceSeries.window(candles, candles.size, config.modelCandleCap)
                for (r in labRules.filter { it.status == LabStatus.ENABLED && it.matches(ser) }) {
                    val agrees = r.callSide == (raw.direction == QuotexDecision.CALL)
                    raw = raw.copy(checks = raw.checks + AgentCheck("Lab rule ${r.name}", agrees, if (agrees) "matches the setup direction" else "points the other way"))
                    if (!agrees) {
                        raw = raw.copy(warnings = raw.warnings + "Lab rule \"${r.name}\" points the other way")
                        if (raw.status == AgentStatus.SETUP_DETECTED) {
                            raw = raw.copy(status = AgentStatus.WATCH, reasons = listOf("Validated lab rule \"${r.name}\" disagrees with this setup.") + raw.reasons)
                        }
                    }
                }
            } catch (e: Exception) {
                // A broken lab rule must never break the analysis.
            }
        }

        // Signal memory: a setup that keeps failing in this regime, or an unstable CALL/PUT flip-flop, becomes WAIT.
        if (raw.status == AgentStatus.SETUP_DETECTED) {
            val key = raw.strategies.sorted().joinToString("+").ifEmpty { "setup" } + ":" + raw.direction.name
            val mv = memory.verdict(key, raw.regime.name, raw.direction, nowMs)
            if (mv.action == MemoryAction.WAIT) {
                raw = raw.copy(status = AgentStatus.WAIT, direction = QuotexDecision.WAIT, reasons = mv.reasons + raw.reasons)
            } else {
                if (mv.action == MemoryAction.REDUCE) {
                    raw = raw.copy(setupScore = raw.setupScore?.let { (it * mv.scoreMultiplier).toInt() }, warnings = raw.warnings + mv.reasons)
                }
                if (!mv.duplicate) memory.record(key, raw.regime.name, raw.direction, nowMs)
            }
        }

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
            memory.resolve(
                p.strategies.sorted().joinToString("+").ifEmpty { "setup" } + ":" + p.direction,
                when (outcome) { JournalOutcome.WIN -> MemoryOutcome.WIN; JournalOutcome.LOSS -> MemoryOutcome.LOSS; else -> MemoryOutcome.DRAW }
            )
        }
        // A pending setup whose expiry candle can never arrive (feed gap) is invalidated rather than left open forever.
        val newest = candles.last().openTimeMs
        for (p in journal.pending()) {
            if (newest > p.expiryMs + candleMs * 3) journal.resolve(p.id, JournalOutcome.INVALIDATED, null)
        }
    }
}
