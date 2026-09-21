package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.CandleBuilder
import com.jarvis.assistant.quotex.analysis.CandleRuns
import com.jarvis.assistant.quotex.analysis.QuotexBacktestEngine
import com.jarvis.assistant.quotex.analysis.QuotexBacktestReport
import com.jarvis.assistant.quotex.analysis.QuotexEngine
import com.jarvis.assistant.quotex.analysis.QuotexPrediction
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
            builder = CandleBuilder(config.candleSeconds)
            lastPrice = null
            _state.update {
                it.copy(candleCount = 0, prediction = null, lastOutcome = null, backtest = null, lastPrice = null, message = "Data deleted.")
            }
        }
    }

    private suspend fun initialiseLocked(forAsset: String?) {
        config = settings.config()
        asset = forAsset
        builder = CandleBuilder(config.candleSeconds)
        lastPrice = null
        candles.clear()
        liveOutcomes.clear()
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
        }
        candles.add(candle)
        if (candles.size > config.maxCandlesKept) {
            val keep = candles.takeLast(config.maxCandlesKept / 2)
            candles.clear()
            candles.addAll(keep)
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
            }
        }
        publishLocked(prediction, resolvedMark)
    }

    private fun publishLocked(prediction: QuotexPrediction?, mark: QuotexOutcomeMark? = null) {
        val liveOn = settings.liveAnalysisEnabled
        val shown = if (liveOn) (prediction ?: engine.latestPrediction()) else null
        val message = when {
            !liveOn && candles.size < config.minCandlesForSignal ->
                "Collecting price history (${candles.size}/${config.minCandlesForSignal} candles). Analysis starts after that."
            !liveOn -> "Live analysis is off. Enable it in Quotex setup when ready."
            else -> shown?.waitReason
        }
        _state.update {
            it.copy(
                ready = true, asset = asset, lastPrice = lastPrice, candleCount = candles.size,
                prediction = shown, liveAnalysisEnabled = liveOn, lastOutcome = mark ?: it.lastOutcome,
                expirySeconds = config.expirySeconds, breakEven = config.breakEvenAccuracy, message = message
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
