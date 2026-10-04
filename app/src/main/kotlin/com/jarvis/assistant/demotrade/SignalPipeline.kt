package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the deterministic pipeline measured for one closed candle (before AI, risk and execution). */
class PipelineResult(
    val data: DataCheck,
    val ind: IndicatorSet?,
    val regime: RegimeResult?,
    val patterns: List<Pattern>,
    val mtf: MtfResult?,
    val votes: List<StrategyVote>,
    val ensemble: EnsembleResult?,
    val tech: ScoreResult?,
    val candleTimeMs: Long,
    val lastClose: Double
) {
    /** Worth asking the AI about? Saves AI calls: only when the deterministic layers already agree on a direction. */
    fun promising(settings: DemoSettings): Boolean {
        val t = tech ?: return false
        val e = ensemble ?: return false
        val r = regime ?: return false
        if (!data.ok) return false
        if (t.direction == Dir.WAIT || t.direction != e.direction) return false
        if (r.regime == Regime.HIGH_VOLATILITY || r.regime == Regime.UNCERTAIN || r.regime == Regime.LOW_VOLATILITY) return false
        if (mtf?.vetoes(t.direction) == true) return false
        return t.score >= settings.weakThreshold && e.score >= settings.weakThreshold
    }
}

object SignalPipeline {
    private const val INDICATOR_WINDOW = 600
    private const val MTF_WINDOW = 1500

    /**
     * Data Validation -> Features -> Technical Analysis -> Regime -> Strategies -> Scoring.
     * Uses ONLY the candles passed in, so calling it with a truncated history reproduces exactly what was knowable then
     * (this is what keeps the backtest free of look-ahead bias).
     */
    fun analyze(candles: List<Candle>, settings: DemoSettings, nowMs: Long, candleMs: Long, checkFresh: Boolean = true): PipelineResult {
        val check = DataValidator.validate(candles, candleMs, nowMs, settings.minCandles, checkFresh)
        val lastT = candles.lastOrNull()?.openTimeMs ?: 0L
        val lastC = candles.lastOrNull()?.close ?: Double.NaN
        if (!check.ok) {
            return PipelineResult(check, null, null, emptyList(), null, emptyList(), null, null, lastT, lastC)
        }
        val window = if (candles.size > INDICATOR_WINDOW) candles.subList(candles.size - INDICATOR_WINDOW, candles.size) else candles
        val mtfSource = if (candles.size > MTF_WINDOW) candles.subList(candles.size - MTF_WINDOW, candles.size) else candles
        val ind = IndicatorSet(window)
        val regime = RegimeDetector.detect(ind)
        val patterns = PatternDetector.detect(ind)
        val mtf = MultiTimeframe.analyze(mtfSource, candleMs, settings.useMultiTimeframe)
        val ctx = AnalysisContext(ind, regime, patterns, mtf)
        val votes = StrategyLibrary.enabled(settings.enabledStrategies).map { it.evaluate(ctx) }
        val ensemble = StrategyEnsemble.combine(votes, settings.minConfirmations)
        val tech = SignalScorer.score(ctx, votes)
        return PipelineResult(check, ind, regime, patterns, mtf, votes, ensemble, tech, lastT, lastC)
    }

    /** Consensus layer: technical score + strategy ensemble + (optional) AI + regime + multi-timeframe -> final decision. */
    fun finalize(
        p: PipelineResult,
        ai: AiOpinion?,
        settings: DemoSettings,
        asset: String,
        entryPrice: Double,
        nowMs: Long
    ): TradeSignal {
        if (!p.data.ok) {
            val prefix = if (p.data.unavailable) "Data: MARKET DATA UNAVAILABLE - " else "Data: "
            return waitSignal(p, settings, asset, entryPrice, nowMs, Regime.UNCERTAIN, prefix + (p.data.reason ?: "invalid data"), 0, null)
        }
        val regime = p.regime!!
        val tech = p.tech!!
        val ens = p.ensemble!!
        val mtf = p.mtf!!
        val raw = ((tech.score + ens.score) / 2.0).roundToInt()

        fun block(reason: String) = waitSignal(p, settings, asset, entryPrice, nowMs, regime.regime, reason, raw, ai)

        when (regime.regime) {
            Regime.HIGH_VOLATILITY -> return block("Regime: high volatility (${regime.reasons.firstOrNull() ?: ""})")
            Regime.UNCERTAIN -> return block("Regime: uncertain market (${regime.reasons.firstOrNull() ?: ""})")
            Regime.LOW_VOLATILITY -> return block("Regime: volatility too low to trade")
            else -> Unit
        }
        if (tech.direction == Dir.WAIT) return block("Score: technical picture is neutral")
        if (ens.direction == Dir.WAIT) return block("Consensus: strategies do not agree (${ens.confirmationText} confirmations, ${ens.opposing} opposing)")
        if (tech.direction != ens.direction) return block("Consensus: technical score ${tech.direction} vs strategies ${ens.direction}")
        val dir = tech.direction
        if (mtf.vetoes(dir)) return block("Multi-timeframe: higher timeframe is ${mtf.higher}, signal is $dir")

        val aiAgrees = settings.useAi && ai != null && !ai.fallback && ai.direction == dir
        if (settings.useAi && ai != null && !ai.fallback && ai.direction == dir.opposite()) {
            return block("AI: AI analysis says ${ai.direction}, engine says $dir")
        }
        if (regime.regime == Regime.RANGE) {
            val srAgrees = p.votes.any {
                (it.strategy == SupportResistanceRejection.name || it.strategy == StochasticReversal.name) && it.direction == dir
            }
            if (!srAgrees) return block("Range filter: range market, only support/resistance or band-reversal setups are allowed")
        }

        var conf: Double = if (aiAgrees) {
            tech.score * 0.4 + ens.score * 0.4 + ai!!.confidence * 0.2
        } else {
            (tech.score + ens.score) / 2.0
        }
        if (settings.useAi && !aiAgrees) conf -= 10.0 // AI abstained / unavailable: less agreement = less confidence
        if (mtf.available && mtf.higher == dir) conf += 3.0
        if (regime.regime == Regime.RANGE) conf -= 6.0
        // Tick activity (only when measured): a surge in the signal's direction adds a little, a dead market takes some away.
        val activity = p.ind?.activityRatioNow() ?: Double.NaN
        val lastCandle = p.ind?.candles?.lastOrNull()
        if (!activity.isNaN() && lastCandle != null) {
            val withDir = (dir == Dir.CALL && lastCandle.close > lastCandle.open) || (dir == Dir.PUT && lastCandle.close < lastCandle.open)
            if (activity >= 1.5 && withDir) conf += 2.0
            if (activity <= 0.4) conf -= 3.0
        }
        val confidence = conf.coerceIn(0.0, 100.0).roundToInt()

        if (confidence < settings.weakThreshold) return block("Threshold: model confidence $confidence is below ${settings.weakThreshold}")

        val agreeing = p.votes.filter { it.direction == dir }
        val confirmations = ArrayList<String>()
        agreeing.forEach { confirmations.add("${it.strategy} ${it.score}") }
        if (mtf.available && mtf.higher == dir) confirmations.add("Higher timeframe agrees")
        if (aiAgrees) confirmations.add("AI agrees (${ai!!.confidence})")
        confirmations.add("Regime ${regime.regime.name}")

        val reasons = ArrayList<String>()
        agreeing.flatMap { it.reasons }.distinct().take(5).forEach { reasons.add(it) }
        if (aiAgrees) ai!!.reasons.take(2).forEach { reasons.add("AI: $it") }
        tech.components.filter { it.points > 0.0 }.sortedByDescending { it.points }.take(3)
            .forEach { reasons.add("${it.name} ${String.format(java.util.Locale.US, "%+.1f", it.points)} pts") }

        val ratio = p.ind?.atrRatio() ?: Double.NaN
        val risk = when {
            regime.regime == Regime.HIGH_VOLATILITY || (!ratio.isNaN() && ratio > 1.5) -> RiskLevel.HIGH
            confidence >= 85 && (regime.regime == Regime.TREND_UP || regime.regime == Regime.TREND_DOWN) &&
                (ratio.isNaN() || ratio in 0.7..1.3) -> RiskLevel.LOW
            else -> RiskLevel.MEDIUM
        }
        return TradeSignal(
            direction = dir,
            confidence = confidence,
            entryPrice = entryPrice,
            timestampMs = nowMs,
            candleTimeMs = p.candleTimeMs,
            expirySeconds = settings.expirySeconds,
            validUntilMs = nowMs + settings.signalValiditySeconds * 1000L,
            regime = regime.regime,
            confirmations = confirmations,
            reasons = reasons,
            riskLevel = risk,
            stake = 0.0,
            strength = settings.strengthOf(confidence),
            asset = asset,
            votes = p.votes,
            components = tech.components,
            technicalScore = tech.score,
            ensembleScore = ens.score,
            aiDirection = ai?.takeIf { settings.useAi }?.direction,
            aiConfidence = ai?.takeIf { settings.useAi && !it.fallback }?.confidence,
            indicators = p.ind?.snapshot() ?: emptyMap(),
            atr = p.ind?.atrNow?.takeIf { !it.isNaN() } ?: 0.0,
            payoutRatio = settings.payoutRatio,
            breakEvenWinRate = settings.breakEvenWinRate,
            mtfNote = mtf.summary,
            blockReason = null
        )
    }

    private fun waitSignal(
        p: PipelineResult, settings: DemoSettings, asset: String, entryPrice: Double, nowMs: Long,
        regime: Regime, reason: String, rawConfidence: Int, ai: AiOpinion?
    ): TradeSignal = TradeSignal(
        direction = Dir.WAIT,
        // A blocked setup never shows a tradeable-looking score: cap it below the WAIT/weak boundary.
        confidence = min(max(rawConfidence, 0), max(0, settings.weakThreshold - 1)),
        entryPrice = if (entryPrice.isNaN()) 0.0 else entryPrice,
        timestampMs = nowMs,
        candleTimeMs = p.candleTimeMs,
        expirySeconds = settings.expirySeconds,
        validUntilMs = nowMs,
        regime = regime,
        confirmations = emptyList(),
        reasons = listOf(reason),
        riskLevel = RiskLevel.HIGH,
        stake = 0.0,
        strength = SignalStrength.NONE,
        asset = asset,
        votes = p.votes,
        components = p.tech?.components ?: emptyList(),
        technicalScore = p.tech?.score ?: 0,
        ensembleScore = p.ensemble?.score ?: 0,
        aiDirection = ai?.takeIf { settings.useAi }?.direction,
        aiConfidence = ai?.takeIf { settings.useAi && !it.fallback }?.confidence,
        indicators = p.ind?.snapshot() ?: emptyMap(),
        atr = p.ind?.atrNow?.takeIf { !it.isNaN() } ?: 0.0,
        payoutRatio = settings.payoutRatio,
        breakEvenWinRate = settings.breakEvenWinRate,
        mtfNote = p.mtf?.summary ?: "",
        blockReason = reason
    )
}
