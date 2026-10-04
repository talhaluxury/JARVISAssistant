package com.jarvis.assistant.ui.screens.demotrade

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.demotrade.BacktestReport
import com.jarvis.assistant.demotrade.CandleSimulator
import com.jarvis.assistant.demotrade.DemoBacktestEngine
import com.jarvis.assistant.demotrade.DemoSettings
import com.jarvis.assistant.demotrade.DemoTradingEngine
import com.jarvis.assistant.demotrade.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BacktestUiState(
    val running: Boolean = false,
    val title: String = "",
    val report: BacktestReport? = null,
    val message: String? = null
)

class DemoTradingViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as JarvisApplication).container
    private val module = container.demoTrading
    val engine: DemoTradingEngine = module.engine
    val state = engine.state

    private val _backtest = MutableStateFlow(BacktestUiState())
    val backtest: StateFlow<BacktestUiState> = _backtest.asStateFlow()

    init {
        module.runtime // make sure the clock/worker is running
    }

    fun update(change: (DemoSettings) -> DemoSettings) {
        module.updateSettings(change)
    }

    fun resetAccount() = engine.resetAccount()

    fun resume() = engine.resume()

    /** Replays the engine over the real candles JARVIS has stored from the screen. */
    fun runBacktestOnStoredCandles() {
        if (_backtest.value.running) return
        _backtest.update { BacktestUiState(running = true, title = "Stored chart candles") }
        viewModelScope.launch {
            try {
                val coordinator = container.quotex.coordinator
                val candles = coordinator.candlesSnapshot()
                val candleSeconds = coordinator.state.value.candleSeconds
                if (candleSeconds <= 0 || candles.isEmpty()) {
                    _backtest.value = BacktestUiState(message = "No stored candles yet. Start Quotex monitoring and let JARVIS collect history first.")
                    return@launch
                }
                val settings = engine.settingsSnapshot()
                if (candles.size < settings.minCandles + 20) {
                    _backtest.value = BacktestUiState(message = "Only ${candles.size} candles stored; at least ${settings.minCandles + 20} are needed for a meaningful backtest.")
                    return@launch
                }
                val report = withContext(Dispatchers.Default) { DemoBacktestEngine.run(candles, settings, candleSeconds * 1000L) }
                _backtest.value = BacktestUiState(title = "Stored chart candles (${candles.size})", report = report)
            } catch (e: Exception) {
                _backtest.value = BacktestUiState(message = "Backtest failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** SIMULATION MODE: artificial candle sequences, fully offline, never touches the live account. */
    fun runSimulation(scenario: Scenario) {
        if (_backtest.value.running) return
        _backtest.update { BacktestUiState(running = true, title = "Simulation: ${scenario.label}") }
        viewModelScope.launch {
            try {
                val settings = engine.settingsSnapshot()
                val report = withContext(Dispatchers.Default) {
                    val candles = CandleSimulator.generate(scenario, 600)
                    DemoBacktestEngine.run(candles, settings, CandleSimulator.CANDLE_MS)
                }
                _backtest.value = BacktestUiState(title = "Simulation: ${scenario.label} (artificial data)", report = report)
            } catch (e: Exception) {
                _backtest.value = BacktestUiState(message = "Simulation failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
