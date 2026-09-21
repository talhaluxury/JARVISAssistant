package com.jarvis.assistant.ui.screens.quotex

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.quotex.QuotexAnalytics
import com.jarvis.assistant.quotex.QuotexModule
import com.jarvis.assistant.quotex.analysis.QuotexBacktestEngine
import com.jarvis.assistant.quotex.analysis.QuotexBacktestReport
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.ocr.QuotexCandleCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Random

/** Offline TEST MODE data. Never mixed into the live database. */
data class QuotexTestState(
    val candles: List<Candle> = emptyList(),
    val info: String = "No test data loaded.",
    val report: QuotexBacktestReport? = null,
    val running: Boolean = false
)

class QuotexViewModel(application: Application) : AndroidViewModel(application) {
    val module: QuotexModule = (application as JarvisApplication).container.quotex
    val state = module.coordinator.state

    private val _analytics = MutableStateFlow<QuotexAnalytics?>(null)
    val analytics: StateFlow<QuotexAnalytics?> = _analytics.asStateFlow()

    private val _test = MutableStateFlow(QuotexTestState())
    val test: StateFlow<QuotexTestState> = _test.asStateFlow()

    private val _liveReport = MutableStateFlow<QuotexBacktestReport?>(null)
    val liveReport: StateFlow<QuotexBacktestReport?> = _liveReport.asStateFlow()

    private val _chat = MutableStateFlow("Ask about the current chart: signal, why, accuracy, price, backtest.")
    val chat: StateFlow<String> = _chat.asStateFlow()

    private val _dataInfo = MutableStateFlow("")
    val dataInfo: StateFlow<String> = _dataInfo.asStateFlow()

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

    // ---- settings ---------------------------------------------------------------------------------------

    fun liveAnalysisEnabled(): Boolean = module.settings.liveAnalysisEnabled
    fun requireVerifiedEdge(): Boolean = module.settings.requireVerifiedEdge
    fun candleSeconds(): Int = module.settings.candleSeconds
    fun expiryCandles(): Int = module.settings.expiryCandles
    fun payout(): Float = module.settings.payout
    fun regionTop(): Float = module.settings.regionTop
    fun regionBottom(): Float = module.settings.regionBottom

    private fun applySettings() {
        viewModelScope.launch {
            module.coordinator.onSettingsChanged()
            refreshAnalytics()
        }
    }

    fun setLiveAnalysis(enabled: Boolean) {
        module.settings.liveAnalysisEnabled = enabled
        applySettings()
    }

    fun setRequireVerifiedEdge(enabled: Boolean) {
        module.settings.requireVerifiedEdge = enabled
        applySettings()
    }

    fun setCandleSeconds(seconds: Int) {
        module.settings.candleSeconds = seconds
        applySettings()
    }

    fun setExpiryCandles(count: Int) {
        module.settings.expiryCandles = count
        applySettings()
    }

    fun setPayout(value: Float) {
        module.settings.payout = value
        applySettings()
    }

    fun saveRegion(top: Float, bottom: Float) {
        if (top < bottom) {
            module.settings.regionTop = top
            module.settings.regionBottom = bottom
        }
    }

    // ---- chat, manual price, backtest ---------------------------------------------------------------------

    fun ask(question: String) {
        if (question.isBlank()) return
        viewModelScope.launch {
            _chat.value = "…"
            _chat.value = try {
                withContext(Dispatchers.Default) { module.chat.answer(question) }
            } catch (e: Exception) {
                "I could not answer that."
            }
        }
    }

    fun addManualPrice(text: String) {
        val price = text.trim().replace(',', '.').toDoubleOrNull()
        if (price == null || price <= 0.0) {
            _dataInfo.value = "Enter a valid price, for example 1.08234."
            return
        }
        viewModelScope.launch {
            val accepted = module.coordinator.addManualPrice(price)
            _dataInfo.value = if (accepted) "Price $price added." else "Price rejected (implausible jump from the last price)."
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

    // ---- backup / restore -----------------------------------------------------------------------------------

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val csv = module.coordinator.exportCsv()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(csv) }
                }
                _dataInfo.value = "Saved ${csv.lines().count { it.isNotBlank() } - 1} candles."
            } catch (e: Exception) {
                _dataInfo.value = "Export failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text == null) {
                    _dataInfo.value = "Could not open that file."
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) { QuotexCandleCsv.parse(text) }
                val added = module.coordinator.restoreCandles(parsed.byAsset)
                _dataInfo.value = "Restored $added new candles (${parsed.total - added} already existed, " +
                    "${parsed.rejectedLines.size} invalid lines skipped)."
                refreshAnalytics()
            } catch (e: Exception) {
                _dataInfo.value = "Restore failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun resetData() {
        viewModelScope.launch {
            module.coordinator.resetData()
            refreshAnalytics()
        }
    }

    // ---- test mode ------------------------------------------------------------------------------------------

    fun importTestCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text == null) {
                    _test.update { it.copy(info = "Could not open that file.") }
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) { QuotexCandleCsv.parse(text) }
                val biggest = parsed.byAsset.entries.maxByOrNull { it.value.size }
                _test.value = QuotexTestState(
                    candles = biggest?.value.orEmpty(),
                    info = "Loaded ${biggest?.value?.size ?: 0} candles of ${biggest?.key ?: "-"}. " +
                        "Rejected ${parsed.rejectedLines.size} lines, ${parsed.duplicates} duplicates."
                )
            } catch (e: Exception) {
                _test.update { it.copy(info = "Import failed: ${e.message ?: "unknown error"}") }
            }
        }
    }

    /** Control experiment: a fair random walk. A working, honest analyzer should show ~50% here. */
    fun generateRandomControl(count: Int = 3000) {
        val random = Random(System.nanoTime())
        val candleMs = module.settings.config().candleMs
        var price = 1.0
        val candles = ArrayList<Candle>(count)
        for (i in 0 until count) {
            val open = price
            price += random.nextGaussian() * 0.0002
            if (price <= 0.1) price = 0.1
            val high = maxOf(open, price)
            val low = minOf(open, price)
            candles.add(Candle(1_700_000_000_000L + i * candleMs, open, high, low, price))
        }
        _test.value = QuotexTestState(
            candles = candles,
            info = "Generated $count random-walk candles (unpredictable by construction). Compare this backtest with real data."
        )
    }

    fun runTestBacktest() {
        val data = _test.value.candles
        if (data.isEmpty()) return
        viewModelScope.launch {
            _test.update { it.copy(running = true, report = null) }
            val report = withContext(Dispatchers.Default) { QuotexBacktestEngine(module.settings.config()).run(data) }
            _test.update { it.copy(running = false, report = report) }
        }
    }
}
