package com.jarvis.assistant.wingo

import com.jarvis.assistant.wingo.analysis.AccuracyWindow
import com.jarvis.assistant.wingo.analysis.BandStats
import com.jarvis.assistant.wingo.analysis.BacktestReport
import com.jarvis.assistant.wingo.analysis.ModelStatus
import com.jarvis.assistant.wingo.analysis.WinGoPrediction
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.RoundResult

enum class ScreenStatus { NOT_STARTED, SEARCHING, TRACKING, NOT_DETECTED }

/** Result of comparing a finished round with the prediction that was made before it. */
data class OutcomeMark(
    val period: String,
    val predicted: BigSmall?,
    val actual: BigSmall,
    val wasSignal: Boolean
) {
    val correct: Boolean? get() = predicted?.let { it == actual }
}

data class WinGoUiState(
    val monitorOn: Boolean = false,
    val captureReady: Boolean = false,
    val screenStatus: ScreenStatus = ScreenStatus.NOT_STARTED,
    val ready: Boolean = false,
    val historyCount: Int = 0,
    val uncertainReadings: Int = 0,
    val prediction: WinGoPrediction? = null,
    val predictionPeriod: String? = null,
    val liveAnalysisEnabled: Boolean = false,
    val lastOutcome: OutcomeMark? = null,
    val recentResults: List<RoundResult> = emptyList(),
    val backtest: BacktestReport? = null,
    val message: String? = null
)

/** Everything the analytics screen and chat need, derived from stored predictions and results. */
data class AnalyticsSnapshot(
    val today: AccuracyWindow,
    val last100: AccuracyWindow,
    val last500: AccuracyWindow,
    val bands: List<BandStats>,
    val bigCount: Int,
    val smallCount: Int,
    val cumulativeAccuracy: List<Double>,
    val agreement: Map<Int, Pair<Int, Int>>,
    val modelStatuses: List<ModelStatus>,
    val recentResults: List<RoundResult>
)
