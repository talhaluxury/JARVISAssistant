package com.jarvis.assistant.ui.screens.wingo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.wingo.AnalyticsSnapshot
import com.jarvis.assistant.wingo.WinGoModule
import com.jarvis.assistant.wingo.analysis.BacktestReport
import com.jarvis.assistant.wingo.analysis.WinGoBacktestEngine
import com.jarvis.assistant.wingo.domain.NormalizedRegion
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.ocr.CsvImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Random

/** Offline TEST MODE data. Never mixed into the live database. */
data class TestModeState(
    val results: List<RoundResult> = emptyList(),
    val info: String = "No test data loaded.",
    val report: BacktestReport? = null,
    val running: Boolean = false
)

class WinGoViewModel(application: Application) : AndroidViewModel(application) {
    val module: WinGoModule = (application as JarvisApplication).container.winGo

    val state = module.coordinator.state

    private val _analytics = MutableStateFlow<AnalyticsSnapshot?>(null)
    val analytics: StateFlow<AnalyticsSnapshot?> = _analytics.asStateFlow()

    private val _test = MutableStateFlow(TestModeState())
    val test: StateFlow<TestModeState> = _test.asStateFlow()

    private val _liveReport = MutableStateFlow<BacktestReport?>(null)
    val liveReport: StateFlow<BacktestReport?> = _liveReport.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refreshAnalytics()
    }

    fun refreshAnalytics() {
        viewModelScope.launch {
            try {
                _analytics.value = module.coordinator.analytics()
            } catch (e: Exception) {
                module.coordinator.setMessage("Could not load analytics.")
            }
        }
    }

    fun setLiveAnalysis(enabled: Boolean) {
        module.settings.liveAnalysisEnabled = enabled
        module.coordinator.onSettingsChanged()
    }

    fun setRequireVerifiedEdge(enabled: Boolean) {
        module.settings.requireVerifiedEdge = enabled
        module.coordinator.onSettingsChanged()
    }

    fun requireVerifiedEdge(): Boolean = module.settings.requireVerifiedEdge
    fun liveAnalysisEnabled(): Boolean = module.settings.liveAnalysisEnabled
    fun savedRegion(): NormalizedRegion? = module.settings.manualRegion

    fun saveRegion(top: Float, bottom: Float) {
        if (top < bottom) module.settings.manualRegion = NormalizedRegion(0f, top, 1f, bottom)
    }

    fun clearRegion() {
        module.settings.manualRegion = null
    }

    fun resetData() {
        viewModelScope.launch {
            module.coordinator.resetData()
            refreshAnalytics()
        }
    }

    fun runStoredBacktest() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _liveReport.value = module.coordinator.runBacktest(null)
            } finally {
                _busy.value = false
            }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text == null) {
                    _test.update { it.copy(info = "Could not open that file.") }
                    return@launch
                }
                val imported = withContext(Dispatchers.Default) { CsvImporter.parse(text) }
                _test.value = TestModeState(
                    results = imported.results,
                    info = "Imported ${imported.results.size} rounds. Rejected ${imported.rejectedLines.size} lines, " +
                        "ignored ${imported.duplicates} duplicates."
                )
            } catch (e: Exception) {
                _test.update { it.copy(info = "Import failed: ${e.message ?: "unknown error"}") }
            }
        }
    }

    /** Control experiment: a genuinely fair coin. A working, honest analyzer should show ~50% here. */
    fun generateRandomControl(count: Int = 3000) {
        val random = Random(System.nanoTime())
        val now = System.currentTimeMillis()
        val base = 20260101100050001L
        val rounds = List(count) { RoundResult((base + it).toString(), random.nextInt(10), now) }
        _test.value = TestModeState(
            results = rounds,
            info = "Generated $count random rounds (a fair, unpredictable game). Compare this backtest with your real data."
        )
    }

    fun runTestBacktest() {
        val data = _test.value.results
        if (data.isEmpty()) return
        viewModelScope.launch {
            _test.update { it.copy(running = true, report = null) }
            val report = withContext(Dispatchers.Default) { WinGoBacktestEngine(module.settings.config()).run(data) }
            _test.update { it.copy(running = false, report = report) }
        }
    }
}
