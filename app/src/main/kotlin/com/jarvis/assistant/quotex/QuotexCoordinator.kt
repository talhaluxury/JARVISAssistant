package com.jarvis.assistant.quotex

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
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private var config: QuotexConfig = settings.config()
    private var engine = QuotexEngine(config)
    /** One walk-forward record per strategy; calibrates each strategy's weight in the confluence engine. */
    private var strategyTrackers: Map<String, StrategyPerformanceTracker> = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
    /** Strategy calls awaiting their outcome, keyed by the candle index at which they resolve. */
    private val pendingStrategyCalls = HashMap<Int, List<Pair<String, QuotexDecision>>>()
    private var signalStateMachine = SignalStateMachine()
    private var riskEngine = com.jarvis.assistant.quotex.risk.RiskEngine(settings.riskConfig(), clock)
    private var builder = CandleBuilder(config.candleSeconds)
    private val candles = ArrayList<Candle>()
    private val liveOutcomes = ArrayList<CallOutcome>()
    private var asset: String? = null
    private var lastPrice: Double? = null
    private var ready = false

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

    /** Called after candle length / expiry / payout / thresholds changed. */
    suspend fun onSettingsChanged() {
        mutex.withLock { initialiseLocked(asset ?: settings.lastAsset) }
    }

    suspend fun resetData() {
        mutex.withLock {
            repository.clear()
            candles.clear()
            liveOutcomes.clear()
            engine = QuotexEngine(config)
            signalStateMachine = SignalStateMachine()
            strategyTrackers = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
            pendingStrategyCalls.clear()
            riskEngine = com.jarvis.assistant.quotex.risk.RiskEngine(settings.riskConfig(), clock)
            builder = CandleBuilder(config.candleSeconds)
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
        riskEngine.updateConfig(settings.riskConfig())
        asset = forAsset
        builder = CandleBuilder(config.candleSeconds)
        lastPrice = null
        candles.clear()
        liveOutcomes.clear()
        signalStateMachine = SignalStateMachine()
        strategyTrackers = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
        pendingStrategyCalls.clear()
        if (forAsset != null) {
            val stored = repository.latestAscending(forAsset, config.maxCandlesKept)
            candles.addAll(CandleRuns.contiguousTail(stored, config.candleMs))
        }
        rebuildEngineLocked()
        ready = true
        publishLocked(null)
    }

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
            val name = reading.asset
            if (name != null && name != asset) {
                settings.lastAsset = name
                initialiseLocked(name)
            }
            val price = reading.price
            if (price == null) {
                noteUnreadable()
                return@withLock
            }
            addTickLocked(clock(), price)
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

    private suspend fun addTickLocked(timeMs: Long, price: Double): Boolean {
        val previous = lastPrice
        if (previous != null && abs(price - previous) / previous > config.maxTickJumpFraction) {
            noteUnreadable() // implausible jump: treated as a misread, not stored
            return false
        }
        lastPrice = price
        val closed = builder.add(timeMs, price)
        if (closed != null) onCandleClosedLocked(closed)
        publishLocked(null)
        return true
    }

    private suspend fun onCandleClosedLocked(candle: Candle) {
        val name = asset ?: "UNKNOWN"
        repository.insert(name, candle)
        val last = candles.lastOrNull()
        val gap = last != null && candle.openTimeMs - last.openTimeMs > config.candleMs * 3
        if (gap) {
            candles.clear()
            engine = QuotexEngine(config)
            pendingStrategyCalls.clear()
            strategyTrackers = defaultStrategies().associate { it.name to StrategyPerformanceTracker() }
            signalStateMachine = SignalStateMachine()
        }
        candles.add(candle)
        if (candles.size > config.maxCandlesKept) {
            val keep = candles.takeLast(config.maxCandlesKept / 2)
            candles.clear()
            candles.addAll(keep)
            pendingStrategyCalls.clear() // their candle-index keys no longer line up after the trim
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
        publishLocked(prediction, resolvedMark, confluence, signalReading?.state)
    }

    private fun publishLocked(
        prediction: QuotexPrediction?, mark: QuotexOutcomeMark? = null,
        confluence: ConfluenceResult? = null, signalState: SignalState? = null
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
        _state.update {
            it.copy(
                ready = true, asset = asset, lastPrice = lastPrice, candleCount = candles.size,
                prediction = shown, liveAnalysisEnabled = liveOn, lastOutcome = mark ?: it.lastOutcome,
                expirySeconds = config.expirySeconds, breakEven = config.breakEvenAccuracy, message = message,
                confluence = if (liveOn && !risk.paused) (confluence ?: it.confluence) else null,
                signalState = if (liveOn && signalState != null) signalState else it.signalState,
                risk = risk
            )
        }
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

    fun currentConfig(): QuotexConfig = config
}
