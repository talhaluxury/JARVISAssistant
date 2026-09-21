package com.jarvis.assistant.quotex

import com.jarvis.assistant.quotex.analysis.QuotexBacktestReport
import com.jarvis.assistant.quotex.analysis.QuotexPrediction
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
    val breakEven: Double = 0.54,
    val message: String? = null
)

data class QuotexAnalytics(
    val session: AccuracyWindow,
    val bands: List<BandStats>,
    val cumulativeAccuracy: List<Double>,
    val modelStatuses: List<ModelStatus>
)
