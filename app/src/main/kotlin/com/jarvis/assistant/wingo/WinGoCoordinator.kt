package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.analysis.AccuracyWindow
import com.jarvis.assistant.wingo.analysis.BacktestReport
import com.jarvis.assistant.wingo.analysis.PerformanceAnalyzer
import com.jarvis.assistant.wingo.analysis.RoundHistory
import com.jarvis.assistant.wingo.analysis.WinGoAnalysisEngine
import com.jarvis.assistant.wingo.analysis.WinGoBacktestEngine
import com.jarvis.assistant.wingo.analysis.WinGoPrediction
import com.jarvis.assistant.wingo.data.GameHistoryRepository
import com.jarvis.assistant.wingo.data.PredictionRepository
import com.jarvis.assistant.wingo.data.toRecord
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.PeriodFormat
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.domain.WinGoConfig
import com.jarvis.assistant.wingo.ocr.CsvExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Glue between the capture pipeline and the analysis engine:
 * confirmed result -> history DB -> resolve the prediction made BEFORE it -> observe -> predict next.
 * The result of a round is never available to the prediction for that round.
 */
class WinGoCoordinator(
    private val history: GameHistoryRepository,
    private val predictions: PredictionRepository,
    private val settings: WinGoSettings,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var config: WinGoConfig = settings.config()
    private var engine = WinGoAnalysisEngine(config)
    private val results = ArrayList<RoundResult>()
    private val numbers = ArrayList<Int>()
    private var currentPrediction: WinGoPrediction? = null
    private var ready = false
    private var roundsSinceBacktest = 0

    private val _state = MutableStateFlow(WinGoUiState())
    val state: StateFlow<WinGoUiState> = _state.asStateFlow()

    // ---- status updates from the capture service -------------------------------------------------

    fun setMonitor(on: Boolean) {
        _state.update {
            it.copy(
                monitorOn = on, captureReady = on,
                screenStatus = if (on) it.screenStatus else ScreenStatus.NOT_STARTED,
                message = if (on) it.message else "WinGo monitoring is off."
            )
        }
    }

    fun setScreenStatus(status: ScreenStatus) {
        _state.update {
            val message = when (status) {
                ScreenStatus.NOT_DETECTED -> "WinGo screen not detected."
                else -> if (it.message == "WinGo screen not detected.") null else it.message
            }
            it.copy(screenStatus = status, message = message)
        }
    }

    fun setMessage(message: String?) {
        _state.update { it.copy(message = message) }
    }

    fun noteUncertain(count: Int) {
        if (count <= 0) return
        _state.update { it.copy(uncertainReadings = it.uncertainReadings + count) }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    suspend fun ensureReady() {
        mutex.withLock { if (!ready) initialiseLocked() }
    }

    /** Called after thresholds / settings changed: rebuilds the engine with the new config. */
    fun onSettingsChanged() {
        scope.launch {
            mutex.withLock { initialiseLocked() }
        }
    }

    suspend fun resetData() {
        mutex.withLock {
            history.clear()
            predictions.clear()
            results.clear()
            numbers.clear()
            currentPrediction = null
            config = settings.config()
            engine = WinGoAnalysisEngine(config)
            ready = true
            _state.update {
                it.copy(
                    historyCount = 0, prediction = null, predictionPeriod = null, lastOutcome = null,
                    recentResults = emptyList(), backtest = null, uncertainReadings = 0,
                    message = "Insufficient historical data."
                )
            }
        }
    }

    private suspend fun initialiseLocked() {
        config = settings.config()
        val loaded = history.latestAscending(HISTORY_CAP)
        results.clear()
        results.addAll(loaded)
        numbers.clear()
        numbers.addAll(loaded.map { it.number })
        val trained = withContext(Dispatchers.Default) { WinGoBacktestEngine(config).runWithEngine(loaded) }
        engine = trained.second
        ready = true
        roundsSinceBacktest = 0
        _state.update { it.copy(backtest = trained.first) }
        refreshPredictionLocked()
    }

    // ---- ingest ----------------------------------------------------------------------------------

    /** Results that passed validation AND were read identically in several samples. */
    suspend fun onConfirmedResults(newResults: List<RoundResult>) {
        ensureReady()
        mutex.withLock {
            var backfilled = false
            var addedLive = 0
            for (r in newResults.sortedBy { it.period }) {
                val newestBefore = results.lastOrNull()?.period
                if (!history.insertIfNew(r)) continue // duplicate period: ignored
                if (newestBefore == null || r.period > newestBefore) {
                    handleLiveResultLocked(r)
                    addedLive++
                } else {
                    insertSortedLocked(r)
                    backfilled = true
                }
            }
            if (addedLive == 0 && !backfilled) return@withLock
            if (backfilled) rebuildEngineLocked()
            refreshPredictionLocked()
            roundsSinceBacktest += addedLive
            if (roundsSinceBacktest >= BACKTEST_REFRESH_EVERY) {
                roundsSinceBacktest = 0
                val snapshot = results.toList()
                val cfg = config
                scope.launch {
                    val report = WinGoBacktestEngine(cfg).run(snapshot)
                    _state.update { it.copy(backtest = report) }
                }
            }
        }
    }

    private suspend fun handleLiveResultLocked(r: RoundResult) {
        val actual = r.bigSmall
        val pending = currentPrediction?.takeIf { it.forPeriod == r.period }
        // The prediction for this round comes from history BEFORE this result is appended.
        val prediction = pending
            ?: engine.predict(RoundHistory.window(numbers, numbers.size, config.modelHistoryCap)).copy(forPeriod = r.period)
        engine.observe(prediction, actual)

        val stored = predictions.resolveStored(r.period, actual)
        val mark = if (stored == null) {
            null
        } else {
            OutcomeMark(r.period, BigSmall.parse(stored.prediction), actual, stored.signal != Signal.WAIT.name)
        }

        results.add(r)
        numbers.add(r.number)
        if (results.size > HISTORY_CAP + TRIM_BATCH) {
            results.subList(0, TRIM_BATCH).clear()
            numbers.subList(0, TRIM_BATCH).clear()
        }
        _state.update { it.copy(lastOutcome = mark ?: it.lastOutcome) }
    }

    private fun insertSortedLocked(r: RoundResult) {
        var index = results.indexOfFirst { it.period > r.period }
        if (index < 0) index = results.size
        results.add(index, r)
        numbers.add(index, r.number)
    }

    private suspend fun rebuildEngineLocked() {
        val snapshot = results.toList()
        val cfg = config
        val trained = withContext(Dispatchers.Default) { WinGoBacktestEngine(cfg).runWithEngine(snapshot) }
        engine = trained.second
        _state.update { it.copy(backtest = trained.first) }
    }

    private suspend fun refreshPredictionLocked() {
        val nextPeriod = results.lastOrNull()?.let { PeriodFormat.next(it.period) }
        val recent = results.takeLast(RECENT_SHOWN).reversed()
        val liveOn = settings.liveAnalysisEnabled
        if (!liveOn) {
            currentPrediction = null
            val message = if (results.size < config.minHistoryForSignal) {
                "Insufficient historical data (${results.size}/${config.minHistoryForSignal} verified rounds)."
            } else {
                "Live analysis is off. Enable it in WinGo setup when you are ready."
            }
            _state.update {
                it.copy(
                    ready = true, historyCount = results.size, prediction = null, predictionPeriod = nextPeriod,
                    liveAnalysisEnabled = false, recentResults = recent, message = message
                )
            }
            return
        }
        val prediction = engine.predict(RoundHistory.window(numbers, numbers.size, config.modelHistoryCap))
            .copy(forPeriod = nextPeriod)
        currentPrediction = prediction
        if (nextPeriod != null && prediction.side != null) {
            predictions.save(prediction.toRecord(nextPeriod, clock()))
        }
        _state.update {
            it.copy(
                ready = true, historyCount = results.size, prediction = prediction, predictionPeriod = nextPeriod,
                liveAnalysisEnabled = true, recentResults = recent, message = prediction.waitReason
            )
        }
    }

    // ---- queries ----------------------------------------------------------------------------------

    /** Backtest on stored history. [lastN] = evaluate roughly the last N rounds (plus warm-up history). */
    suspend fun runBacktest(lastN: Int?): BacktestReport {
        ensureReady()
        val snapshot = mutex.withLock { results.toList() }
        val cfg = config
        val slice = if (lastN == null) snapshot else snapshot.takeLast(lastN + cfg.minHistoryForSignal)
        val report = withContext(Dispatchers.Default) { WinGoBacktestEngine(cfg).run(slice) }
        if (lastN == null) _state.update { it.copy(backtest = report) }
        return report
    }

    suspend fun analytics(): AnalyticsSnapshot {
        ensureReady()
        val cfg = config
        val last500 = predictions.latestOutcomes(500)
        val today = PerformanceAnalyzer.window(predictions.outcomesSince(startOfTodayMillis()))
        val snapshot = mutex.withLock { results.toList() }
        val last100Results = snapshot.takeLast(100)
        return AnalyticsSnapshot(
            today = today,
            last100 = PerformanceAnalyzer.window(last500.takeLast(100)),
            last500 = PerformanceAnalyzer.window(last500),
            bands = PerformanceAnalyzer.byBand(last500, cfg),
            bigCount = last100Results.count { it.bigSmall == BigSmall.BIG },
            smallCount = last100Results.count { it.bigSmall == BigSmall.SMALL },
            cumulativeAccuracy = PerformanceAnalyzer.cumulativeAccuracy(last500),
            agreement = PerformanceAnalyzer.byAgreement(last500),
            modelStatuses = mutex.withLock { engine.modelStatuses() },
            recentResults = snapshot.takeLast(RECENT_SHOWN).reversed()
        )
    }

    /** All stored verified rounds as CSV text (backup). */
    suspend fun exportCsv(): String {
        ensureReady()
        return CsvExporter.toCsv(history.allAscending())
    }

    /**
     * Restores rounds from a backup (e.g. after reinstalling). Existing periods are kept as they are,
     * new ones are added, then the engine is rebuilt from the merged history. Returns how many were new.
     */
    suspend fun restoreResults(restored: List<RoundResult>): Int {
        ensureReady()
        return mutex.withLock {
            val added = history.insertAllNew(restored)
            if (added > 0) initialiseLocked()
            added
        }
    }

    suspend fun recentResults(count: Int): List<RoundResult> {
        ensureReady()
        return mutex.withLock { results.takeLast(count).reversed() }
    }

    fun emptyWindow(): AccuracyWindow = PerformanceAnalyzer.window(emptyList())

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = clock()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val HISTORY_CAP = 3000
        const val TRIM_BATCH = 500
        const val RECENT_SHOWN = 12
        const val BACKTEST_REFRESH_EVERY = 50
    }
}
