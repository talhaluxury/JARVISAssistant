package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.agent.AgentSnapshot
import com.jarvis.assistant.quotex.analysis.ConfluenceResult
import com.jarvis.assistant.quotex.analysis.QuotexBacktestReport
import com.jarvis.assistant.quotex.analysis.QuotexPrediction
import com.jarvis.assistant.quotex.analysis.SignalState
import com.jarvis.assistant.quotex.risk.RiskSnapshot
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.analysis.AccuracyWindow
import com.jarvis.assistant.wingo.analysis.BandStats
import com.jarvis.assistant.wingo.analysis.ModelStatus

/** Outcome of a call once its expiry passed. */
data class QuotexOutcomeMark(val decision: QuotexDecision, val higher: Boolean, val wasSignal: Boolean) {
    val correct: Boolean get() = (decision == QuotexDecision.CALL) == higher
}

data class QuotexUiState(
    val monitorOn: Boolean = false,
    val captureReady: Boolean = false,
    val screenStatus: ScreenStatus = ScreenStatus.NOT_STARTED,
    val ready: Boolean = false,
    val asset: String? = null,
    val lastPrice: Double? = null,
    val candleCount: Int = 0,
    val unreadableTicks: Int = 0,
    val prediction: QuotexPrediction? = null,
    val liveAnalysisEnabled: Boolean = false,
    val lastOutcome: QuotexOutcomeMark? = null,
    val backtest: QuotexBacktestReport? = null,
    val expirySeconds: Int = 60,
    /** Candle length JARVIS is really using (it follows the chart automatically). 0 = not known yet. */
    val candleSeconds: Int = 0,
    val breakEven: Double = 0.54,
    val message: String? = null,
    /** Strategy-library confluence read and its signal-state-machine status (sections 11-14). */
    val confluence: ConfluenceResult? = null,
    val signalState: SignalState = SignalState.SCANNING,
    /** Discipline layer only (section 25) - never affects a real trade, only whether JARVIS surfaces a setup. */
    val risk: RiskSnapshot? = null,
    /** Full-pipeline read (data quality, regime, 10 strategies, lifecycle) - see the quotex.agent package. */
    val agent: AgentSnapshot? = null,
    /** Last thing the screen reader reported (why the chart / price / asset was or was not found). */
    val readerNote: String = "",
    /** Whether real chart candles are refining the sampled ones (section 31). */
    val chartStatus: String = "",
    /** Labelled guess for the NEXT candle (shown with a countdown to its open). NOT a signal, not a probability. */
    val nextGuess: com.jarvis.assistant.quotex.agent.QuickGuess? = null,
    /** The guess that was made for the candle that is forming right now (valid as an entry only for its first seconds). */
    val entryGuess: com.jarvis.assistant.quotex.agent.QuickGuess? = null,
    val guessHits: Int = 0,
    val guessTotal: Int = 0
)

data class QuotexAnalytics(
    val session: AccuracyWindow,
    val bands: List<BandStats>,
    val cumulativeAccuracy: List<Double>,
    val modelStatuses: List<ModelStatus>
)
