package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.agent.AgentBacktestReport
import com.jarvis.assistant.quotex.agent.AgentBacktester
import com.jarvis.assistant.quotex.agent.AgentConfig
import com.jarvis.assistant.quotex.agent.AgentMode
import com.jarvis.assistant.quotex.agent.AgentRuntime
import com.jarvis.assistant.quotex.agent.AgentSnapshot
import com.jarvis.assistant.quotex.agent.InMemoryJournalStore
import com.jarvis.assistant.quotex.agent.JournalEntry
import com.jarvis.assistant.quotex.agent.JournalStore
import com.jarvis.assistant.quotex.agent.QuotexJournal
import com.jarvis.assistant.quotex.agent.NewsRiskFilter
import com.jarvis.assistant.quotex.pro.LabPaperTracker
import com.jarvis.assistant.quotex.pro.LabRuleStore
import com.jarvis.assistant.quotex.pro.LabStatus
import com.jarvis.assistant.quotex.pro.NewsEventCodec
import com.jarvis.assistant.quotex.pro.PaperStat
import com.jarvis.assistant.quotex.analysis.CandleBuilder
import com.jarvis.assistant.quotex.analysis.CandleRuns
import com.jarvis.assistant.quotex.analysis.ConfluenceEngine
import com.jarvis.assistant.quotex.analysis.ConfluenceResult
import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.QuotexBacktestEngine
import com.jarvis.assistant.quotex.analysis.QuotexBacktestReport
import com.jarvis.assistant.quotex.analysis.QuotexEngine
import com.jarvis.assistant.quotex.analysis.QuotexPrediction
import com.jarvis.assistant.quotex.analysis.SignalState
import com.jarvis.assistant.quotex.analysis.SignalStateMachine
import com.jarvis.assistant.quotex.analysis.StrategyPerformanceTracker
import com.jarvis.assistant.quotex.analysis.StrategyStatus
import com.jarvis.assistant.quotex.analysis.ConfluenceBacktestEngine
import com.jarvis.assistant.quotex.analysis.ConfluenceBacktestReport
import com.jarvis.assistant.quotex.analysis.defaultStrategies
import com.jarvis.assistant.quotex.data.QuotexCandleRepository
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.domain.QuotexConfig
import com.jarvis.assistant.quotex.ocr.QuotexCandleCsv
import com.jarvis.assistant.quotex.ocr.QuotexReading
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.analysis.CallOutcome
import com.jarvis.assistant.wingo.analysis.PerformanceAnalyzer
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.BigSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Screen reading -> price ticks -> candles -> walk-forward analysis. Analysis only: nothing here can
 * place, prepare or confirm a trade.
 */
class QuotexCoordinator(
    private val repository: QuotexCandleRepository,
    private val settings: QuotexSettings,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tradeJournal: com.jarvis.assistant.quotex.data.QuotexJournalRepository? = null,
    agentJournalStore: JournalStore = InMemoryJournalStore(),
    /** Learns from every closed candle which quick-guess signals work on this asset (see [GuessLearner]). */
    private val learner: com.jarvis.assistant.quotex.agent.GuessLearner = com.jarvis.assistant.quotex.agent.GuessLearner()
) {
    private val mutex = Mutex()
    private var config: QuotexConfig = settings.config()
    private var engine = QuotexEngine(config)
    /** One walk-forward record per strategy; calibrates each strategy's weight in the confluence engine. */
    private var strategyTrackers: Map<String, StrategyPerformanceTracker> = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
    /** Strategy calls awaiting their outcome, keyed by the candle index at which they resolve. */
    private val pendingStrategyCalls = HashMap<Int, List<Pair<String, QuotexDecision>>>()
    /** Room journal row id + predicted direction, awaiting its outcome, keyed by the resolving candle index. */
    private val pendingJournal = HashMap<Int, Pair<Long, QuotexDecision>>()
    private var candidateAsset: String? = null
    private var candidateHits = 0
    private var signalStateMachine = SignalStateMachine()
    private var riskEngine = com.jarvis.assistant.quotex.risk.RiskEngine(settings.riskConfig(), clock)
    private val agentJournal = QuotexJournal(agentJournalStore)

    private fun buildAgentConfig(): AgentConfig = AgentConfig(
        candleSeconds = config.candleSeconds, expiryCandles = config.expiryCandles, minCandles = config.minCandlesForSignal,
        modelCandleCap = config.modelCandleCap, breakEven = config.breakEvenAccuracy,
        requireVerifiedEdge = config.requireVerifiedEdge, edgeMinSamples = config.edgeMinSamples,
        edgeZThreshold = config.edgeZThreshold,
        minSetupScore = settings.minSetupScore, minDataQualityScore = settings.minDataQualityScore,
        disabledStrategies = settings.disabledStrategies.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        middleSeconds = settings.mtfMiddleSeconds, higherSeconds = settings.mtfHigherSeconds,
        blockHighVolatility = settings.blockHighVolatility
    )

    /** UNAVAILABLE unless the user maintains a calendar; then their events (possibly none) are the truth. */
    private fun buildNewsFilter(): NewsRiskFilter =
        if (settings.newsCalendarEnabled) {
            NewsRiskFilter(NewsEventCodec.parse(settings.newsEvents), highWindowMs = settings.newsHighWindowMin * 60_000L)
        } else {
            NewsRiskFilter(null)
        }

    private var labTracker = LabPaperTracker(config.expiryCandles, config.candleMs).also { it.load(settings.labPaperStats) }

    /** Applies changed analysis settings (score/data-quality minimums, strategies, news, timeframes) without restarting capture. */
    suspend fun applySettings() {
        mutex.withLock {
            agentRuntime = AgentRuntime(buildAgentConfig(), agentJournal, buildNewsFilter(), agentRuntime.mode)
            lastAgentBacktest = null
            candlesSinceBacktest = 0
        }
    }

    fun labSummary(): String = com.jarvis.assistant.quotex.pro.ProNarrator.lab(LabRuleStore.parse(settings.labRules)) { labTracker.stat(it) }

    fun labPaperStat(id: String): PaperStat = labTracker.stat(id)

    fun resetLabPaper(id: String) {
        labTracker.reset(id)
        settings.labPaperStats = labTracker.dump()
    }

    /** Section 26: SIMULATION until the user turns live analysis on. */
    private var agentRuntime = AgentRuntime(buildAgentConfig(), agentJournal, buildNewsFilter())
    /** Candles closed since the last walk-forward refresh of the edge gate's evidence. */
    private var candlesSinceBacktest = 0
    private var lastAgentBacktest: AgentBacktestReport? = null
    private var builder = CandleBuilder(config.candleSeconds)
    private val candles = ArrayList<Candle>()
    private val liveOutcomes = ArrayList<CallOutcome>()
    private var asset: String? = null
    private var lastPrice: Double? = null
    private var ready = false
    /** OCR confidence of every accepted tick of the candle currently being built (section 31). */
    private val ocrSamples = ArrayList<Double>()
    /** Closed candles read from the chart image, keyed by open time; they only ever refine sampled candles. */
    private val chartCandles = LinkedHashMap<Long, Candle>()
    private var chartRefined = 0
    private var chartSeen = 0
    /** Why the latest chart read was rejected (shown to the user so a failing detector can be diagnosed). */
    private var chartNote = ""

    /** Optional hook for the DEMO paper-trading engine. Implementations only enqueue work; they never block or trade for real. */
    @Volatile var demoFeed: com.jarvis.assistant.demotrade.DemoFeed? = null

    private val _state = MutableStateFlow(QuotexUiState())
    val state: StateFlow<QuotexUiState> = _state.asStateFlow()

    // ---- status from the capture service -----------------------------------------------------------

    fun setMonitor(on: Boolean) {
        _state.update {
            it.copy(
                monitorOn = on, captureReady = on,
                screenStatus = if (on) it.screenStatus else ScreenStatus.NOT_STARTED,
                message = if (on) it.message else "Quotex monitoring is off."
            )
        }
    }

    fun setScreenStatus(status: ScreenStatus) {
        _state.update { it.copy(screenStatus = status) }
    }

    fun setMessage(message: String?) {
        _state.update { it.copy(message = message) }
    }

    fun noteUnreadable() {
        _state.update { it.copy(unreadableTicks = it.unreadableTicks + 1) }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    suspend fun ensureReady() {
        mutex.withLock { if (!ready) initialiseLocked(settings.lastAsset) }
    }

    /**
     * The chart's candle length really changed: stored candles sit on the OLD time grid, so mixing them with the new
     * ones would create fake gaps. They are deleted for the current asset, then everything restarts on the new length.
     */
    suspend fun onTimeframeChanged() {
        mutex.withLock {
            (asset ?: settings.lastAsset)?.let { repository.clearAsset(it) }
            initialiseLocked(asset ?: settings.lastAsset)
        }
    }

    /** Called after candle length / expiry / payout / thresholds changed. */
    suspend fun onSettingsChanged() {
        mutex.withLock { initialiseLocked(asset ?: settings.lastAsset) }
    }

    suspend fun resetData() {
        mutex.withLock {
            repository.clear()
            tradeJournal?.clear()
            candles.clear()
            liveOutcomes.clear()
            engine = QuotexEngine(config)
            signalStateMachine = SignalStateMachine()
            strategyTrackers = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
            pendingStrategyCalls.clear()
            pendingJournal.clear()
            riskEngine = com.jarvis.assistant.quotex.risk.RiskEngine(settings.riskConfig(), clock)
            builder = CandleBuilder(config.candleSeconds)
            ocrSamples.clear()
            chartCandles.clear()
            lastPrice = null
            _state.update {
                it.copy(
                    candleCount = 0, prediction = null, lastOutcome = null, backtest = null, lastPrice = null,
                    message = "Data deleted.", confluence = null, signalState = SignalState.SCANNING
                )
            }
        }
    }

    private suspend fun initialiseLocked(forAsset: String?) {
        config = settings.config()
        agentRuntime = AgentRuntime(buildAgentConfig(), agentJournal, buildNewsFilter(), agentRuntime.mode)
        labTracker = LabPaperTracker(config.expiryCandles, config.candleMs).also { it.load(settings.labPaperStats) }
        lastAgentBacktest = null
        candlesSinceBacktest = 0
        riskEngine.updateConfig(settings.riskConfig())
        asset = forAsset
        builder = CandleBuilder(config.candleSeconds)
        ocrSamples.clear()
        chartCandles.clear()
        lastPrice = null
        candles.clear()
        liveOutcomes.clear()
        signalStateMachine = SignalStateMachine()
        strategyTrackers = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
        pendingStrategyCalls.clear()
        pendingJournal.clear()
        if (forAsset != null) {
            val stored = repository.latestAscending(forAsset, config.maxCandlesKept)
            candles.addAll(CandleRuns.contiguousTail(stored, config.candleMs, resumeGapCandles()))
        }
        if (candles.size > com.jarvis.assistant.quotex.agent.QuickGuessEngine.MIN_CANDLES) {
            val history = candles.toList()
            val scope = learnScope()
            withContext(Dispatchers.Default) { learner.trainOnHistory(history, scope) }
        }
        rebuildEngineLocked()
        ready = true
        publishLocked(null)
    }

    /** Gap tolerance in candles: at least [MAX_GAP_CANDLES], and long enough to cover [RESUME_WITHIN_MS]. */
    private fun resumeGapCandles(): Int =
        maxOf(MAX_GAP_CANDLES, (RESUME_WITHIN_MS / config.candleMs).toInt())

    private suspend fun rebuildEngineLocked() {
        val snapshot = candles.toList()
        val cfg = config
        val trained = withContext(Dispatchers.Default) { QuotexBacktestEngine(cfg).runWithEngine(snapshot) }
        engine = trained.second
        _state.update { it.copy(backtest = trained.first) }
    }

    // ---- ingest ----------------------------------------------------------------------------------------

    /** One OCR reading of the screen. Unreadable readings are counted and otherwise ignored. */
    suspend fun onReading(reading: QuotexReading) {
        ensureReady()
        mutex.withLock {
            _state.update { it.copy(readerNote = reading.note) }
            val name = reading.asset
            if (name != null && name != asset) {
                // Switching asset wipes the candle history, so one misread word must never trigger it.
                if (name == candidateAsset) candidateHits++ else { candidateAsset = name; candidateHits = 1 }
                if (candidateHits >= ASSET_SWITCH_HITS) {
                    candidateAsset = null
                    candidateHits = 0
                    settings.lastAsset = name
                    initialiseLocked(name)
                }
            } else if (name == asset) {
                candidateAsset = null
                candidateHits = 0
            }
            val price = reading.price
            if (price == null) {
                noteUnreadable()
                return@withLock
            }
            val conf = reading.confidence
            if (conf != null && conf < OCR_TICK_FLOOR) {
                // Far too uncertain to build a candle from: counted as unreadable, never stored.
                noteUnreadable()
                return@withLock
            }
            addTickLocked(clock(), price, conf)
        }
    }

    /**
     * Candles read from the chart image of the SAME frame as [ocrPrice]. Accepted only when the forming candle's
     * close agrees with the OCR live price; anything else is ignored. Call this BEFORE [onReading] for that frame,
     * so the candle that closes on this frame can already be refined with it.
     */
    suspend fun onChartDetection(detection: com.jarvis.assistant.quotex.ocr.ChartDetection, ocrPrice: Double) {
        mutex.withLock {
            if (detection.candles.isEmpty() || detection.confidence < CHART_MIN_CONFIDENCE) {
                chartNote = detection.note.ifBlank { "confidence too low" }
                return@withLock
            }
            if (!com.jarvis.assistant.quotex.ocr.ChartCandleDetector.agreesWith(detection, ocrPrice, ocrPrice * CHART_PRICE_TOLERANCE)) {
                chartNote = "chart close ${detection.candles.last().close} differs from OCR price $ocrPrice"
                return@withLock
            }
            chartNote = ""
            chartSeen++
            for (c in detection.closed) chartCandles[c.openTimeMs] = c
            while (chartCandles.size > CHART_KEEP) chartCandles.remove(chartCandles.keys.first())
        }
    }

    /**
     * Older candles read from the chart after scrolling it back in time. Candles already stored are never
     * overwritten. Returns how many were new, or -1 when the asset is not known yet (nothing is saved then).
     */
    suspend fun importHistory(history: List<Candle>): Int {
        ensureReady()
        return mutex.withLock {
            val name = asset ?: return@withLock -1
            if (history.isEmpty()) return@withLock 0
            var added = 0
            for (c in history) if (repository.insert(name, c)) added++
            if (added > 0) initialiseLocked(name)
            added
        }
    }

    /** Lets the user type the asset when OCR cannot read the name from the chart. */
    suspend fun setAssetManually(name: String) {
        ensureReady()
        mutex.withLock {
            if (name != asset) {
                settings.lastAsset = name
                initialiseLocked(name)
            }
            candidateAsset = null
            candidateHits = 0
            _state.update { it.copy(message = "Asset set manually to $name.") }
        }
    }

    /** Manual price entry (testing, or when OCR cannot read the chart). */
    suspend fun addManualPrice(price: Double): Boolean {
        ensureReady()
        return mutex.withLock {
            if (price <= 0.0) return@withLock false
            if (asset == null) {
                asset = "MANUAL"
                settings.lastAsset = "MANUAL"
            }
            addTickLocked(clock(), price)
        }
    }

    private suspend fun addTickLocked(timeMs: Long, price: Double, ocrConfidence: Double? = null): Boolean {
        val previous = lastPrice
        if (previous != null && abs(price - previous) / previous > config.maxTickJumpFraction) {
            noteUnreadable() // implausible jump: treated as a misread, not stored
            return false
        }
        lastPrice = price
        demoFeed?.onTick(price)
        val closed = builder.add(timeMs, price)
        if (closed != null) {
            // The tick that closed the candle belongs to the NEXT candle, so score the closed one first.
            val closedConfidence = if (ocrSamples.isEmpty()) null else ocrSamples.average()
            ocrSamples.clear()
            if (ocrConfidence != null) ocrSamples.add(ocrConfidence)
            onCandleClosedLocked(closed, closedConfidence)
        } else if (ocrConfidence != null) {
            ocrSamples.add(ocrConfidence)
        }
        publishLocked(null)
        return true
    }

    private suspend fun onCandleClosedLocked(sampled: Candle, ocrConfidence: Double? = null) {
        val candle = if (settings.useChartCandles) {
            val refined = com.jarvis.assistant.quotex.ocr.ChartRefiner.refine(sampled, chartCandles[sampled.openTimeMs])
            if (refined !== sampled) chartRefined++
            // the chart image knows nothing about tick activity: keep the count measured while the candle was being built
            if (refined !== sampled && refined.ticks != sampled.ticks) refined.copy(ticks = sampled.ticks) else refined
        } else {
            sampled
        }
        val name = asset ?: "UNKNOWN"
        repository.insert(name, candle)
        val last = candles.lastOrNull()
        val gap = last != null && candle.openTimeMs - last.openTimeMs > config.candleMs * (resumeGapCandles() + 1)
        if (gap) {
            candles.clear()
            engine = QuotexEngine(config)
            pendingStrategyCalls.clear()
            pendingJournal.clear()
            strategyTrackers = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
            signalStateMachine = SignalStateMachine()
        }
        candles.add(candle)
        scoreGuessLocked(candle)
        if (candles.size > config.maxCandlesKept) {
            val keep = candles.takeLast(config.maxCandlesKept / 2)
            candles.clear()
            candles.addAll(keep)
            pendingStrategyCalls.clear() // their candle-index keys no longer line up after the trim
            pendingJournal.clear()
            rebuildEngineLocked()
            publishLocked(engine.latestPrediction())
            return
        }
        var resolvedMark: QuotexOutcomeMark? = null
        val prediction = engine.step(candles, candles.lastIndex) { _, predicted, higher ->
            if (predicted.candleCount >= config.minCandlesForSignal && predicted.lean != QuotexDecision.WAIT && higher != null) {
                liveOutcomes.add(
                    CallOutcome(
                        period = "live", side = if (predicted.lean == QuotexDecision.CALL) BigSmall.BIG else BigSmall.SMALL,
                        confidence = predicted.confidence, level = predicted.level, signal = predicted.signal,
                        agree = predicted.agree, totalModels = predicted.totalModels,
                        actual = if (higher) BigSmall.BIG else BigSmall.SMALL
                    )
                )
                if (liveOutcomes.size > 500) liveOutcomes.removeAt(0)
                resolvedMark = QuotexOutcomeMark(predicted.lean, higher, predicted.isSignal)
                if (predicted.isSignal) {
                    val won = (predicted.lean == QuotexDecision.CALL) == higher
                    riskEngine.record(won, config.payout)
                }
            }
        }

        // Resolve any strategy calls whose expiry just arrived, BEFORE using their record to weigh the
        // next call - the same order as the walk-forward backtest, so live and backtest calibrate alike.
        pendingStrategyCalls.remove(candles.lastIndex)?.forEach { (strategyName, direction) ->
            val startIdx = candles.lastIndex - config.expiryCandles
            if (startIdx >= 0) {
                val startPrice = candles[startIdx].close
                val endPrice = candles[candles.lastIndex].close
                if (endPrice != startPrice) {
                    strategyTrackers[strategyName]?.record((direction == QuotexDecision.CALL) == (endPrice > startPrice))
                }
            }
        }

        pendingJournal.remove(candles.lastIndex)?.let { (journalId, predictedDirection) ->
            val startIdx = candles.lastIndex - config.expiryCandles
            if (startIdx >= 0) {
                val startPrice = candles[startIdx].close
                val endPrice = candles[candles.lastIndex].close
                if (endPrice != startPrice) {
                    val higher = endPrice > startPrice
                    val actualDirection = if (higher) QuotexDecision.CALL else QuotexDecision.PUT
                    tradeJournal?.resolve(journalId, clock(), actualDirection.name, actualDirection == predictedDirection)
                }
            }
        }

        val dataQualityOk = candles.size >= config.minCandlesForSignal
        val confluence = if (dataQualityOk) {
            val series = PriceSeries.window(candles, candles.size, config.modelCandleCap)
            val weights = strategyTrackers.mapValues { it.value.weight() }
            val result = ConfluenceEngine(defaultStrategies(), weights).evaluate(
                series, MarketStructure.trendLabel(series), MarketStructure.structureLabel(MarketStructure.swings(series)), MarketStructure.volatilityLabel(series)
            )
            val leaning = result.strategyResults.filter { it.direction != QuotexDecision.WAIT }.map { it.strategyName to it.direction }
            if (leaning.isNotEmpty()) pendingStrategyCalls[candles.lastIndex + config.expiryCandles] = leaning
            result
        } else {
            null
        }
        // Advance the state machine exactly once per closed candle, whether or not confluence ran.
        val signalReading = confluence?.let { signalStateMachine.update(it, dataQualityOk) }
        if (prediction.isSignal && prediction.lean != QuotexDecision.WAIT && tradeJournal != null) {
            val entryPrice = candles[candles.lastIndex].close
            val reason = prediction.waitReason ?: confluence?.reason
                ?: "${prediction.agree}/${prediction.totalModels} models agree on ${prediction.lean.name} at ${Fmt.pct(prediction.confidence)} confidence."
            val id = tradeJournal.insert(
                com.jarvis.assistant.quotex.data.QuotexJournalEntity(
                    timestamp = clock(), asset = asset ?: "UNKNOWN", candleSeconds = config.candleSeconds,
                    expiryCandles = config.expiryCandles, direction = prediction.lean.name, confidence = prediction.confidence,
                    agree = prediction.agree, totalModels = prediction.totalModels, trend = prediction.trend.name,
                    volatility = prediction.volatility.name, confluenceQuality = confluence?.quality?.name ?: "NOT_EVALUATED",
                    signalState = signalReading?.state?.name ?: "SCANNING", entryPrice = entryPrice, reasonSummary = reason
                )
            )
            pendingJournal[candles.lastIndex + config.expiryCandles] = Pair(id, prediction.lean)
        }

        refreshAgentBacktestLocked()
        agentRuntime.mode = if (settings.liveAnalysisEnabled) AgentMode.LIVE_ANALYSIS else AgentMode.SIMULATION
        val labRules = LabRuleStore.parse(settings.labRules).map { it.toRule() }
        val riskNow = riskEngine.snapshot()
        val agentSnapshot = agentRuntime.onCandleClosed(
            candles = candles.toList(), nowMs = clock(), asset = name,
            weights = strategyTrackers.mapValues { it.value.weight() },
            riskPausedReason = if (riskNow.paused) "${riskNow.reason}" else null,
            ocrConfidence = ocrConfidence,
            labRules = labRules
        )
        try {
            if (labTracker.onCandleClosed(labRules.filter { it.status == LabStatus.PAPER_TESTING || it.status == LabStatus.ENABLED }, candles.toList())) {
                settings.labPaperStats = labTracker.dump()
            }
        } catch (e: Exception) {
            // Lab observation is optional; never let it break live analysis.
        }
        demoFeed?.onCandleClosed(candles.toList(), config.candleMs, name, lastPrice)
        publishLocked(prediction, resolvedMark, confluence, signalReading?.state, agentSnapshot)
    }

    /**
     * Section 20: the edge gate is fed by the walk-forward OUT-OF-SAMPLE record, re-measured every
     * [BACKTEST_REFRESH_CANDLES] closed candles (and once as soon as enough history exists). The last
     * [BACKTEST_MAX_CANDLES] candles are replayed with a coarser step to keep this cheap on a phone.
     */
    private suspend fun refreshAgentBacktestLocked() {
        candlesSinceBacktest++
        val enough = candles.size >= config.minCandlesForSignal + BACKTEST_MIN_EXTRA
        val due = lastAgentBacktest == null || candlesSinceBacktest >= BACKTEST_REFRESH_CANDLES
        if (!enough || !due) return
        candlesSinceBacktest = 0
        val snapshot = candles.takeLast(BACKTEST_MAX_CANDLES)
        val agentCfg = buildAgentConfig()
        val report = withContext(Dispatchers.Default) {
            AgentBacktester(agentCfg).run(snapshot, step = agentCfg.expiryCandles * 2)
        }
        lastAgentBacktest = report
        agentRuntime.setBacktest(report)
    }

    private fun publishLocked(
        prediction: QuotexPrediction?, mark: QuotexOutcomeMark? = null,
        confluence: ConfluenceResult? = null, signalState: SignalState? = null, agent: AgentSnapshot? = null
    ) {
        val liveOn = settings.liveAnalysisEnabled
        val risk = riskEngine.snapshot()
        val shown = if (liveOn && !risk.paused) (prediction ?: engine.latestPrediction()) else null
        val message = when {
            risk.paused -> "TRADING PAUSED — ${risk.reason}"
            !liveOn && candles.size < config.minCandlesForSignal ->
                "Collecting price history (${candles.size}/${config.minCandlesForSignal} candles). Analysis starts after that."
            !liveOn -> "Live analysis is off. Enable it in Quotex setup when ready."
            else -> shown?.waitReason
        }
        refreshGuessesLocked()
        val currentOpenMs = Math.floorDiv(clock(), config.candleMs) * config.candleMs
        val nextOpenMs = currentOpenMs + config.candleMs
        _state.update {
            it.copy(
                ready = true, asset = asset, lastPrice = lastPrice, candleCount = candles.size,
                prediction = shown, liveAnalysisEnabled = liveOn, lastOutcome = mark ?: it.lastOutcome,
                expirySeconds = config.expirySeconds, candleSeconds = config.candleSeconds, breakEven = config.breakEvenAccuracy, message = message,
                confluence = if (liveOn && !risk.paused) (confluence ?: it.confluence) else null,
                signalState = if (liveOn && signalState != null) signalState else it.signalState,
                risk = risk, agent = agent ?: it.agent,
                chartStatus = chartStatusText(),
                nextGuess = guessesByOpen[nextOpenMs],
                entryGuess = guessesByOpen[currentOpenMs],
                recentUp = candles.takeLast(10).map { c -> c.close > c.open },
                lastClosedOpenMs = candles.lastOrNull()?.openTimeMs ?: 0L,
                lastClosedUp = candles.lastOrNull()?.let { c -> if (c.close == c.open) null else c.close > c.open },
                guessHits = guessHits, guessTotal = guessTotal,
                learner = learner.stats()
            )
        }
    }

    private val guessesByOpen = LinkedHashMap<Long, com.jarvis.assistant.quotex.agent.QuickGuess>()
    private var guessHits = 0
    private var guessTotal = 0

    /** Builds the guess for the next candle from closed candles plus the still-forming one (using the live price). */
    private fun learnScope(): String = "${asset ?: "?"}@${config.candleSeconds}"

    private fun refreshGuessesLocked() {
        val candleMs = config.candleMs
        val now = clock()
        val currentOpen = Math.floorDiv(now, candleMs) * candleMs
        val nextOpen = currentOpen + candleMs
        val price = lastPrice
        val closed = candles.filter { it.openTimeMs < currentOpen }
        if (closed.isEmpty()) return
        val series = if (price != null) {
            val prev = closed.last().close
            closed + Candle(currentOpen, prev, maxOf(prev, price), minOf(prev, price), price)
        } else closed
        com.jarvis.assistant.quotex.agent.QuickGuessEngine.guess(series, nextOpen)?.let {
            guessesByOpen[nextOpen] = it.copy(learnedUp = learner.predictUp(it.features))
        }
        while (guessesByOpen.size > 8) guessesByOpen.remove(guessesByOpen.keys.first())
    }

    /** Scores the guess that was made for a candle once that candle has closed. Flat candles are skipped. */
    private fun scoreGuessLocked(candle: Candle) {
        val g = guessesByOpen.remove(candle.openTimeMs) ?: return
        if (candle.close == candle.open) return
        guessTotal++
        if (g.up == (candle.close > candle.open)) guessHits++
        learner.learn(g.features, g.up, candle.close > candle.open, candle.openTimeMs, learnScope())
        learner.save()
    }

    private fun chartStatusText(): String = when {
        !settings.useChartCandles -> "OFF (candles from sampled prices)"
        chartSeen == 0 -> "no chart candles read yet (sampled prices only)" + if (chartNote.isNotBlank()) ": $chartNote" else ""
        else -> "$chartRefined candle(s) refined from chart, $chartSeen frames accepted"
    }

    // ---- queries -----------------------------------------------------------------------------------------

    suspend fun runBacktest(lastN: Int?): QuotexBacktestReport {
        ensureReady()
        val snapshot = mutex.withLock { candles.toList() }
        val cfg = config
        val slice = if (lastN == null) snapshot else snapshot.takeLast(lastN + cfg.minCandlesForSignal)
        val report = withContext(Dispatchers.Default) { QuotexBacktestEngine(cfg).run(slice) }
        if (lastN == null) _state.update { it.copy(backtest = report) }
        return report
    }

    /** Walk-forward test of the strategy library + confluence engine (sections 18-19), separate from the ensemble backtest above. */
    suspend fun runConfluenceBacktest(lastN: Int?): ConfluenceBacktestReport {
        ensureReady()
        val cfg = config
        val snapshot = mutex.withLock { candles.toList() }
        val slice = if (lastN == null) snapshot else snapshot.takeLast(lastN + cfg.minCandlesForSignal)
        return withContext(Dispatchers.Default) {
            ConfluenceBacktestEngine(defaultStrategies(), cfg.minCandlesForSignal, cfg.modelCandleCap).run(slice, cfg.expiryCandles)
        }
    }

    /** Walk-forward test of the full agent pipeline with train / validation / out-of-sample segments (sections 18-21). */
    suspend fun runAgentBacktest(lastN: Int?): AgentBacktestReport {
        ensureReady()
        val cfg = config
        val agentCfg = buildAgentConfig()
        val snapshot = mutex.withLock { candles.toList() }
        val slice = if (lastN == null) snapshot else snapshot.takeLast(lastN + cfg.minCandlesForSignal)
        return withContext(Dispatchers.Default) { AgentBacktester(agentCfg).run(slice) }
    }

    /** Copy of the stored closed candles (chronological) for the Strategy Lab. */
    suspend fun candlesSnapshot(): List<Candle> = mutex.withLock { candles.toList() }

    /** Newest-first journal entries (section 27). */
    fun journalLast(n: Int): List<JournalEntry> = agentJournal.last(n)

    fun journalSince(startMs: Long): List<JournalEntry> = agentJournal.since(startMs)

    fun journal(): QuotexJournal = agentJournal

    fun agentSnapshot(): AgentSnapshot? = _state.value.agent

    /** Live, walk-forward-calibrated weight and record of each strategy in the library. */
    suspend fun strategyStatuses(): List<StrategyStatus> {
        ensureReady()
        return mutex.withLock {
            strategyTrackers.map { (name, t) -> StrategyStatus(name, t.samples, t.accuracy, t.weight()) }
        }
    }

    /**
     * A human can always pause or resume JARVIS's displayed setups regardless of what the risk engine has
     * decided - it is a discipline aid, never something that overrides the user's own judgement or that
     * touches the platform's own risk controls.
     */
    suspend fun setRiskPaused(paused: Boolean) {
        ensureReady()
        mutex.withLock {
            riskEngine.setUserPaused(paused)
            publishLocked(null)
        }
    }

    suspend fun riskSnapshot(): com.jarvis.assistant.quotex.risk.RiskSnapshot {
        ensureReady()
        return mutex.withLock { riskEngine.snapshot() }
    }

    /** Every logged signal since local midnight (section 27 - "show today's performance"). */
    suspend fun journalToday(): List<com.jarvis.assistant.quotex.data.JournalEntry> {
        ensureReady()
        val startOfDay = run {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = clock()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        return tradeJournal?.since(startOfDay) ?: emptyList()
    }

    /** The most recent [limit] logged signals, newest first (section 27 - "show my last N setups"). */
    suspend fun journalRecent(limit: Int): List<com.jarvis.assistant.quotex.data.JournalEntry> {
        ensureReady()
        return tradeJournal?.latest(limit) ?: emptyList()
    }

    /** The most recent resolved loss, with the reasoning that was frozen at the moment it was signalled. */
    suspend fun lastFailedSetup(): com.jarvis.assistant.quotex.data.JournalEntry? {
        ensureReady()
        return tradeJournal?.lastLoss()
    }

    suspend fun analytics(): QuotexAnalytics {
        ensureReady()
        return mutex.withLock {
            val outcomes = liveOutcomes.toList()
            QuotexAnalytics(
                session = PerformanceAnalyzer.window(outcomes),
                bands = PerformanceAnalyzer.byBand(outcomes, config.toWinGoConfig()),
                cumulativeAccuracy = PerformanceAnalyzer.cumulativeAccuracy(outcomes),
                modelStatuses = engine.modelStatuses()
            )
        }
    }

    /** All stored candles of every asset as CSV (backup). */
    suspend fun exportCsv(): String {
        ensureReady()
        return QuotexCandleCsv.toCsv(repository.allByAsset())
    }

    /** Adds candles from a backup; returns how many were new. The current asset is reloaded afterwards. */
    suspend fun restoreCandles(byAsset: Map<String, List<Candle>>): Int {
        ensureReady()
        return mutex.withLock {
            val added = repository.insertAll(byAsset)
            if (added > 0) initialiseLocked(asset ?: byAsset.keys.firstOrNull())
            added
        }
    }

    /** The newest [n] stored candles, oldest first (for explanations and the AI record). */
    suspend fun candlesTail(n: Int): List<Candle> {
        ensureReady()
        return mutex.withLock { candles.takeLast(n) }
    }

    fun currentConfig(): QuotexConfig = config

    private companion object {
        const val ASSET_SWITCH_HITS = 3
        /** Readings the OCR engine itself scored below this are not turned into candles at all. */
        const val OCR_TICK_FLOOR = 0.35
        const val BACKTEST_REFRESH_CANDLES = 100
        const val BACKTEST_MIN_EXTRA = 150
        const val BACKTEST_MAX_CANDLES = 1200
        const val CHART_MIN_CONFIDENCE = 0.7
        /** Forming candle close vs OCR live price, relative (0.02% ~ 2 pips on EUR/USD). */
        const val CHART_PRICE_TOLERANCE = 0.0002
        const val CHART_KEEP = 200
        /**
         * Missing candles tolerated before the in-memory history is restarted. A few unreadable seconds (the price
         * chip hiding an axis label, a slow OCR frame) must not wipe everything. The gaps are NOT filled with made-up
         * candles: the data-quality check still counts them and lowers trust accordingly.
         */
        const val MAX_GAP_CANDLES = 5

        /**
         * Longest break (app closed, phone restarted, monitoring paused) after which saved history is still resumed
         * instead of restarting from 1. Longer breaks restart, because stale prices would mislead the analysis.
         * The break is still counted as a gap by the data-quality check; nothing is filled in.
         */
        const val RESUME_WITHIN_MS = 10 * 60 * 1000L
    }
}
