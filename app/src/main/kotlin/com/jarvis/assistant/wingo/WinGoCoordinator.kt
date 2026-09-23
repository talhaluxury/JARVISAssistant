package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.analysis.AccuracyWindow
import com.jarvis.assistant.wingo.analysis.BacktestReport
import com.jarvis.assistant.wingo.analysis.PatternBacktest
import com.jarvis.assistant.wingo.analysis.PatternBacktester
import com.jarvis.assistant.wingo.analysis.PerformanceAnalyzer
import com.jarvis.assistant.wingo.analysis.PredictionVerifier
import com.jarvis.assistant.wingo.analysis.RoundHistory
import com.jarvis.assistant.wingo.analysis.WinGoAnalysisEngine
import com.jarvis.assistant.wingo.analysis.WinGoBacktestEngine
import com.jarvis.assistant.wingo.analysis.WinGoPrediction
import com.jarvis.assistant.wingo.data.GameHistoryRepository
import com.jarvis.assistant.wingo.data.PredictionRepository
import com.jarvis.assistant.wingo.data.toRecord
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.PeriodFormat
import com.jarvis.assistant.wingo.domain.PeriodGaps
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.Signal
import com.jarvis.assistant.wingo.domain.WinGoConfig
import com.jarvis.assistant.wingo.ocr.CsvExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private var dirty = false
    private var rebuildJob: Job? = null
    private var browsing = false
    private var backfilledTotal = 0
    private var phaseJob: Job? = null

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
                ScreenStatus.NOT_DETECTED -> "WIN GO SCREEN NOT DETECTED"
                else -> if (it.message == "WIN GO SCREEN NOT DETECTED") null else it.message
            }
            it.copy(screenStatus = status, message = message)
        }
    }

    /** History pager ("3/50") as read from the screen. */
    fun setPage(current: Int?, total: Int?) {
        _state.update { if (it.pageCurrent == current && it.pageTotal == total) it else it.copy(pageCurrent = current, pageTotal = total) }
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
            rebuildJob?.cancel()
            dirty = false
            browsing = false
            backfilledTotal = 0
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
        rebuildJob?.cancel()
        dirty = false
        browsing = false
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
        startTimelineLocked(afterVerification = false)
    }

    // ---- ingest ----------------------------------------------------------------------------------

    /**
     * Results that passed validation AND were read identically in several samples. Rows newer than the
     * newest stored round are "live" (they resolve predictions). Older rows - e.g. from history pages you
     * flip back to, or rounds that were missed - are back-filled into their place and analysis is rebuilt
     * once after the burst, not once per page.
     */
    suspend fun onConfirmedResults(newResults: List<RoundResult>) {
        ensureReady()
        mutex.withLock {
            var backfilled = 0
            var addedLive = 0
            var rejectedJumps = 0
            for (r in newResults.sortedBy { it.period }) {
                val newestBefore = results.lastOrNull()
                if (newestBefore != null && r.period > newestBefore.period && isImplausibleLiveJump(newestBefore, r)) {
                    rejectedJumps++
                    continue
                }
                if (!history.insertIfNew(r)) continue // duplicate period: ignored
                if (newestBefore == null || r.period > newestBefore.period) {
                    handleLiveResultLocked(r)
                    addedLive++
                } else {
                    insertSortedLocked(r)
                    backfilled++
                }
            }
            if (rejectedJumps > 0) noteUncertain(rejectedJumps)
            if (addedLive == 0 && backfilled == 0) return@withLock
            browsing = addedLive == 0
            if (backfilled > 0) {
                backfilledTotal += backfilled
                dirty = true
            }
            if (addedLive > 0) {
                rebuildJob?.cancel()
                setPhase(RoundPhase.ANALYZING)
                if (dirty) {
                    rebuildEngineLocked()
                    dirty = false
                }
                refreshPredictionLocked()
                startTimelineLocked(afterVerification = _state.value.lastVerification?.targetPeriod == results.lastOrNull()?.period)
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
            } else {
                currentPrediction = null
                val page = _state.value.pageCurrent
                val total = _state.value.pageTotal
                val where = if (page != null && total != null) " (history page $page/$total)" else ""
                _state.update { it.copy(prediction = null) }
                publishCountsLocked("Saved $backfilled older rounds$where. Analysis is paused while you browse history.")
                scheduleRebuildLocked()
            }
        }
    }

    /**
     * The newest period currently readable on screen (from any trusted row, even one already stored).
     * If it is older than the newest stored round, an older history page is showing: live signals pause.
     * When it catches up again (page 1), the engine is brought up to date and live analysis resumes.
     */
    suspend fun onVisiblePeriods(newestVisible: String) {
        mutex.withLock {
            if (!ready) return@withLock
            val newestStored = results.lastOrNull()?.period ?: return@withLock
            if (newestVisible > newestStored && !browsing) {
                // A round newer than anything stored is on screen (not yet confirmed by a second read).
                _state.update {
                    if (it.phase == RoundPhase.PREDICTION_READY || it.phase == RoundPhase.WAITING_FOR_RESULT ||
                        it.phase == RoundPhase.PREDICTION_VERIFIED
                    ) it.copy(phase = RoundPhase.RESULT_DETECTED) else it
                }
                return@withLock
            }
            val nowBrowsing = newestVisible < newestStored
            if (nowBrowsing == browsing) return@withLock
            browsing = nowBrowsing
            if (nowBrowsing) {
                currentPrediction = null
                phaseJob?.cancel()
                _state.update {
                    it.copy(
                        prediction = null, browsingHistory = true, phase = RoundPhase.IDLE,
                        message = "Browsing older history: analysis is paused. Go back to page 1 for live signals."
                    )
                }
            } else {
                if (dirty) {
                    rebuildJob?.cancel()
                    rebuildEngineLocked()
                    dirty = false
                }
                refreshPredictionLocked()
                startTimelineLocked(afterVerification = false)
            }
        }
    }

    /** Rebuild the engine ~2.5 s after the last back-filled page, so flipping through 50 pages stays cheap. */
    private fun scheduleRebuildLocked() {
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            delay(REBUILD_DEBOUNCE_MS)
            mutex.withLock {
                if (dirty) {
                    rebuildEngineLocked()
                    dirty = false
                    if (browsing) publishCountsLocked(null) else refreshPredictionLocked()
                }
            }
        }
    }

    /** Counts, missing-round gaps and recent results - cheap, no model work. */
    private fun publishCountsLocked(message: String?) {
        val gaps = PeriodGaps.find(results.map { it.period })
        val newest = results.lastOrNull()?.period
        val infos = gaps.takeLast(MAX_GAPS_SHOWN).reversed().map { g ->
            GapInfo(g.firstMissing, g.lastMissing, g.count, newest?.let { PeriodGaps.pageHint(it, g.lastMissing) })
        }
        val missing = gaps.sumOf { it.count }
        _state.update {
            it.copy(
                historyCount = results.size, recentResults = results.takeLast(RECENT_SHOWN).reversed(),
                missingRounds = missing, gaps = infos, backfilledSession = backfilledTotal,
                browsingHistory = browsing, message = message ?: it.message
            )
        }
    }

    private suspend fun handleLiveResultLocked(r: RoundResult) {
        val actual = r.bigSmall
        setPhase(RoundPhase.VERIFYING)
        val pending = currentPrediction?.takeIf { it.forPeriod == r.period }
        // The prediction for this round comes from history BEFORE this result is appended.
        val prediction = pending
            ?: engine.predict(RoundHistory.window(numbers, numbers.size, config.modelHistoryCap)).copy(forPeriod = r.period)
        engine.observe(prediction, actual)

        val verifiedAt = clock()
        val stored = predictions.resolveStored(r.period, actual, verifiedAt)
        val verification = if (stored == null) null else PredictionVerifier.verify(r.period, BigSmall.parse(stored.prediction), r.number)
        val mark = if (stored == null) {
            null
        } else {
            OutcomeMark(
                r.period, BigSmall.parse(stored.prediction), actual, stored.signal != Signal.WAIT.name,
                actualNumber = r.number, probBig = stored.probability
            )
        }

        results.add(r)
        numbers.add(r.number)
        if (results.size > HISTORY_CAP + TRIM_BATCH) {
            results.subList(0, TRIM_BATCH).clear()
            numbers.subList(0, TRIM_BATCH).clear()
        }
        _state.update {
            it.copy(
                lastOutcome = mark ?: it.lastOutcome, lastVerification = verification ?: it.lastVerification,
                phase = if (verification != null) RoundPhase.PREDICTION_VERIFIED else it.phase
            )
        }
    }

    private fun setPhase(phase: RoundPhase) {
        _state.update { if (it.phase == phase) it else it.copy(phase = phase) }
    }

    /**
     * After a fresh estimate exists: (verified ->) ready -> waiting for the result. The estimate itself is already
     * on screen the whole time; the phase only tells the user where the round is.
     */
    private fun startTimelineLocked(afterVerification: Boolean) {
        phaseJob?.cancel()
        if (_state.value.prediction == null) {
            setPhase(RoundPhase.IDLE)
            return
        }
        setPhase(if (afterVerification) RoundPhase.PREDICTION_VERIFIED else RoundPhase.PREDICTION_READY)
        phaseJob = scope.launch {
            if (afterVerification) {
                delay(PHASE_HOLD_MS)
                _state.update { if (it.phase == RoundPhase.PREDICTION_VERIFIED) it.copy(phase = RoundPhase.PREDICTION_READY) else it }
            }
            delay(PHASE_HOLD_MS + 500L)
            _state.update {
                if (it.phase == RoundPhase.PREDICTION_READY || it.phase == RoundPhase.PREDICTION_VERIFIED) {
                    it.copy(phase = RoundPhase.WAITING_FOR_RESULT)
                } else {
                    it
                }
            }
        }
    }

    /**
     * True when [r] looks like a misread rather than a real result: monitoring never stopped (the previous
     * verified round was very recent) yet the period jumped far more than a normal round-to-round step, on
     * the same day. A real absence (app closed, page browsed away) always shows as a large TIME gap too, so
     * this only catches the case where time barely passed but the period leapt - the signature of a misread
     * digit, not a skipped round.
     */
    private fun isImplausibleLiveJump(previous: RoundResult, r: RoundResult): Boolean {
        if (PeriodFormat.dayOf(previous.period) != PeriodFormat.dayOf(r.period)) return false
        val prevValue = previous.period.toLongOrNull() ?: return false
        val newValue = r.period.toLongOrNull() ?: return false
        val delta = newValue - prevValue
        if (delta <= config.maxPlausibleLiveJump) return false
        val elapsed = r.timestamp - previous.timestamp
        return elapsed in 0 until config.liveJumpContinuityMs
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
        refreshPredictionCoreLocked()
        publishCountsLocked(null)
    }

    private suspend fun refreshPredictionCoreLocked() {
        val nextPeriod = results.lastOrNull()?.let { PeriodFormat.next(it.period) }
        val recent = results.takeLast(RECENT_SHOWN).reversed()
        val liveOn = settings.liveAnalysisEnabled
        if (browsing) {
            // A prediction for "the next round" would be stale while older pages are on screen.
            currentPrediction = null
            _state.update {
                it.copy(
                    ready = true, prediction = null, predictionPeriod = nextPeriod, liveAnalysisEnabled = liveOn,
                    message = "Browsing older history: analysis is paused. Go back to page 1 for live signals."
                )
            }
            return
        }
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

    /** "Backtest this pattern": walk-forward test of the exact sequence the current estimate leans on. */
    suspend fun backtestCurrentPattern(): PatternBacktest? {
        ensureReady()
        val context = _state.value.prediction?.pattern?.context ?: return null
        val bits = mutex.withLock { results.map { if (it.number >= 5) 1 else 0 }.toIntArray() }
        return withContext(Dispatchers.Default) { PatternBacktester.runLen(bits, context) }
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
        const val REBUILD_DEBOUNCE_MS = 2500L
        const val MAX_GAPS_SHOWN = 8
        const val PHASE_HOLD_MS = 2500L
    }
}
