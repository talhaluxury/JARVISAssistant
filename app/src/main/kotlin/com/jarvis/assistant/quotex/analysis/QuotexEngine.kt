package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.quotex.domain.QuotexConfig
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.analysis.EdgeAssessment
import com.jarvis.assistant.wingo.analysis.EnsemblePredictor
import com.jarvis.assistant.wingo.analysis.ModelOutput
import com.jarvis.assistant.wingo.analysis.ModelStatus
import com.jarvis.assistant.wingo.analysis.ModelTracker
import com.jarvis.assistant.wingo.analysis.RollingHits
import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.ConfidenceLevel
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.Signal

data class QuotexPrediction(
    /** Raw ensemble lean: CALL (higher), PUT (lower) or WAIT (no lean). */
    val lean: QuotexDecision,
    /** What is surfaced to the user: the lean only when a signal passed every gate, otherwise WAIT. */
    val decision: QuotexDecision,
    val probUp: Double,
    val confidence: Double,
    val level: ConfidenceLevel,
    val signal: Signal,
    val agree: Int,
    val voting: Int,
    val totalModels: Int,
    val isCandidate: Boolean,
    val waitReason: String?,
    val outputs: List<ModelOutput>,
    val weights: Map<String, Double>,
    val candleCount: Int,
    val expiryCandles: Int,
    val edge: EdgeAssessment
) {
    val isSignal: Boolean get() = signal != Signal.WAIT
}

/**
 * Same design as the WinGo engine: an ensemble whose weights come from each model's walk-forward record,
 * and a gate that keeps the answer at WAIT until a statistically real edge has been measured. Analysis
 * only - there is no order, account or execution concept anywhere in this package.
 */
class QuotexEngine(
    val config: QuotexConfig = QuotexConfig(),
    private val models: List<QuotexModel> = defaultQuotexModels()
) {
    private val ensemble = EnsemblePredictor()
    private val trackers: Map<String, ModelTracker> = models.associate { it.name to ModelTracker() }
    private val edgeRecord = RollingHits(config.edgeLookback)
    private val pending = ArrayDeque<Pair<Int, QuotexPrediction>>()

    fun edgeAssessment(): EdgeAssessment {
        val n = edgeRecord.samples
        val z = edgeRecord.zScore()
        val acc = edgeRecord.accuracy
        val verified = n >= config.edgeMinSamples && z != null && z >= config.edgeZThreshold
        val summary = if (n == 0 || acc == null || z == null) {
            "No measured record of candidate calls yet"
        } else {
            "Independent candidate calls hit ${edgeRecord.hits}/$n (${Fmt.pct(acc, 1)}, z=${Fmt.num(z)}); " +
                "need at least ${config.edgeMinSamples} calls and z >= ${Fmt.num(config.edgeZThreshold, 2)}"
        }
        return EdgeAssessment(n, edgeRecord.hits, acc, z, verified, summary)
    }

    fun modelStatuses(): List<ModelStatus> = models.map {
        val t = trackers[it.name]
        ModelStatus(it.name, t?.samples ?: 0, t?.accuracy, t?.weight() ?: 1.0)
    }

    /** The most recent prediction still waiting for its outcome (i.e. the "current" one). */
    fun latestPrediction(): QuotexPrediction? = pending.lastOrNull()?.second

    /** Pure with respect to engine state. */
    fun predict(series: PriceSeries): QuotexPrediction {
        val edge = edgeAssessment()
        val horizon = config.expiryCandles
        if (series.size < config.minCandlesForSignal) {
            return blank(series.size, edge, "Insufficient price history (${series.size}/${config.minCandlesForSignal} candles).")
        }
        val outputs = models.map { it.predict(series, horizon) }
        val weights = models.associate { it.name to (trackers[it.name]?.weight() ?: 1.0) }
        val combined = ensemble.combine(outputs, weights)
        val level = config.levelFor(combined.confidence)
        val lean = when (combined.side) {
            BigSmall.BIG -> QuotexDecision.CALL
            BigSmall.SMALL -> QuotexDecision.PUT
            null -> QuotexDecision.WAIT
        }

        var isCandidate = false
        var signal = Signal.WAIT
        val reason: String? = when {
            lean == QuotexDecision.WAIT || level == ConfidenceLevel.VERY_LOW ->
                "WAIT — insufficient signal. Ensemble confidence ${Fmt.pct(combined.confidence)} is below the ${Fmt.pct(config.lowThreshold)} threshold."
            combined.weightedAgreement < config.minWeightedAgreement ->
                "Signal unclear — WAIT. Only ${combined.agree} of ${combined.voting} voting models agree."
            else -> {
                isCandidate = true
                if (config.requireVerifiedEdge && !edge.verified) {
                    "WAIT — no statistically verified edge yet. ${edge.summary}."
                } else {
                    signal = Signal.from(level)
                    null
                }
            }
        }
        return QuotexPrediction(
            lean = lean, decision = if (signal == Signal.WAIT) QuotexDecision.WAIT else lean,
            probUp = combined.probBig, confidence = combined.confidence, level = level, signal = signal,
            agree = combined.agree, voting = combined.voting, totalModels = combined.totalModels,
            isCandidate = isCandidate, waitReason = reason, outputs = outputs, weights = weights,
            candleCount = series.size, expiryCandles = horizon, edge = edge
        )
    }

    /** Feeds back a resolved outcome. [higher] is null when price ended exactly where it started (ignored). */
    fun observe(prediction: QuotexPrediction, higher: Boolean?, countForEdge: Boolean) {
        val up = higher ?: return
        for (o in prediction.outputs) {
            val side = o.prediction ?: continue
            trackers[o.modelName]?.record((side == BigSmall.BIG) == up)
        }
        if (countForEdge && prediction.isCandidate && prediction.lean != QuotexDecision.WAIT) {
            edgeRecord.record((prediction.lean == QuotexDecision.CALL) == up)
        }
    }

    /**
     * One walk-forward step at candle [index]: first resolve predictions whose expiry has now passed
     * (their outcome is candle[idx + horizon] vs candle[idx], both known by [index]), then predict from
     * candles 0..index only. Edge statistics use non-overlapping calls (every horizon-th candle) so that
     * overlapping expiries are not counted as independent evidence.
     */
    fun step(
        all: List<Candle>,
        index: Int,
        onResolved: ((idx: Int, prediction: QuotexPrediction, higher: Boolean?) -> Unit)? = null
    ): QuotexPrediction {
        val horizon = config.expiryCandles
        while (pending.isNotEmpty() && pending.first().first + horizon <= index) {
            val (idx, prediction) = pending.removeFirst()
            val start = all[idx].close
            val end = all[idx + horizon].close
            val higher: Boolean? = if (end == start) null else end > start
            observe(prediction, higher, countForEdge = idx % horizon == 0)
            onResolved?.invoke(idx, prediction, higher)
        }
        val series = PriceSeries.window(all, index + 1, config.modelCandleCap)
        val prediction = predict(series)
        pending.addLast(Pair(index, prediction))
        return prediction
    }

    fun walkForward(
        all: List<Candle>,
        onResolved: ((idx: Int, prediction: QuotexPrediction, higher: Boolean?) -> Unit)? = null
    ) {
        for (j in all.indices) step(all, j, onResolved)
    }

    private fun blank(candleCount: Int, edge: EdgeAssessment, reason: String) = QuotexPrediction(
        lean = QuotexDecision.WAIT, decision = QuotexDecision.WAIT, probUp = 0.5, confidence = 0.5,
        level = ConfidenceLevel.VERY_LOW, signal = Signal.WAIT, agree = 0, voting = 0, totalModels = models.size,
        isCandidate = false, waitReason = reason, outputs = emptyList(), weights = emptyMap(),
        candleCount = candleCount, expiryCandles = config.expiryCandles, edge = edge
    )
}
