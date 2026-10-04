package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.TimeframePlan
import com.jarvis.assistant.quotex.analysis.ConfluenceEngine
import com.jarvis.assistant.quotex.analysis.ConfluenceResult
import com.jarvis.assistant.quotex.analysis.MarketStructure
import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.analysis.SetupQuality
import com.jarvis.assistant.quotex.analysis.StrategyResult
import com.jarvis.assistant.quotex.analysis.StructureEvents
import com.jarvis.assistant.quotex.analysis.StructureLabel
import com.jarvis.assistant.quotex.analysis.TrendState
import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.quotex.domain.Candle
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.quotex.pro.EntryAssessment
import com.jarvis.assistant.quotex.pro.EntryQuality
import com.jarvis.assistant.quotex.pro.EntryQualityEvaluator
import com.jarvis.assistant.quotex.pro.FakeBreakKind
import com.jarvis.assistant.quotex.pro.FakeBreakout
import com.jarvis.assistant.quotex.pro.FakeBreakoutDetector
import com.jarvis.assistant.quotex.pro.ScoreInput
import com.jarvis.assistant.quotex.pro.SetupScorer
import kotlin.math.abs
import kotlin.math.sqrt

/** Final user-facing states (section 40). */
enum class AgentStatus(val emoji: String, val label: String) {
    SETUP_DETECTED("🟢", "SETUP DETECTED"),
    WATCH("🟡", "WATCH"),
    WAIT("🔵", "WAIT"),
    NO_TRADE("🔴", "NO TRADE"),
    DATA_UNCERTAIN("⚪", "DATA UNCERTAIN")
}

/** Measured record of the strategies that fired, used for the historical-performance gate (section 14, step 10). */
data class HistoricalEvidence(val samples: Int, val hits: Int) {
    val accuracy: Double? get() = if (samples == 0) null else hits.toDouble() / samples
}

data class EdgeCheck(val verified: Boolean, val samples: Int, val accuracy: Double?, val z: Double?, val summary: String)

object EdgeTest {
    /** One-sided z-test of the hit rate against break-even. Needs [minSamples] AND z >= [zThreshold]. */
    fun check(evidence: HistoricalEvidence?, breakEven: Double, minSamples: Int = 100, zThreshold: Double = 2.33): EdgeCheck {
        if (evidence == null || evidence.samples == 0) return EdgeCheck(false, 0, null, null, "No measured record yet (need $minSamples setups).")
        val acc = evidence.accuracy ?: return EdgeCheck(false, evidence.samples, null, null, "No measured record yet.")
        val sd = sqrt(breakEven * (1.0 - breakEven) / evidence.samples)
        val z = if (sd > 0.0) (acc - breakEven) / sd else 0.0
        val verified = evidence.samples >= minSamples && z >= zThreshold
        val pct = (acc * 1000).toInt() / 10.0
        val summary = "Record ${evidence.hits}/${evidence.samples} ($pct%), break-even ${(breakEven * 1000).toInt() / 10.0}%, z=${(z * 100).toInt() / 100.0}; " +
            "needs at least $minSamples setups and z >= $zThreshold."
        return EdgeCheck(verified, evidence.samples, acc, z, summary)
    }
}

data class AgentCheck(val step: String, val passed: Boolean, val detail: String)

data class AgentConfig(
    val candleSeconds: Int = 15,
    val expiryCandles: Int = 4,
    val minCandles: Int = 150,
    val modelCandleCap: Int = 1500,
    val breakEven: Double = 1.0 / 1.85,
    val requireVerifiedEdge: Boolean = true,
    val edgeMinSamples: Int = 100,
    val edgeZThreshold: Double = 2.33,
    val minConditionsFraction: Double = 0.6,
    val opposingLevelAtr: Double = 0.5,
    val minSetupScore: Int = 0,
    val minDataQualityScore: Int = 0,
    val disabledStrategies: Set<String> = emptySet(),
    /** 0 = automatic timeframe hierarchy. */
    val middleSeconds: Int = 0,
    val higherSeconds: Int = 0,
    val blockHighVolatility: Boolean = false
)

data class AgentReport(
    val status: AgentStatus,
    val direction: QuotexDecision,
    val quality: SetupQuality,
    val dataQuality: DataQuality,
    val dataSummary: String,
    val trend: TrendState,
    val structure: StructureLabel,
    val volatility: VolatilityState,
    val regime: MarketRegime,
    val session: MarketSession,
    val newsRisk: NewsRisk,
    val conditionsMet: Int,
    val conditionsTotal: Int,
    val strategies: List<String>,
    val checks: List<AgentCheck>,
    val reasons: List<String>,
    val edge: EdgeCheck?,
    val timeMs: Long,
    val lastPrice: Double?,
    val atr: Double?,
    val strategyVersion: String = AnalysisVersions.STRATEGY_VERSION,
    val analysisVersion: String = AnalysisVersions.ANALYSIS_VERSION,
    val indicatorSettings: String = IndicatorSettings().describe(),
    val confluence: ConfluenceResult? = null,
    /** Setup strength 0-100 (NOT a win probability); null when no directional setup was evaluated. */
    val setupScore: Int? = null,
    val entry: EntryAssessment? = null,
    val fakeBreakout: FakeBreakout? = null,
    val warnings: List<String> = emptyList()
) {
    val headlineReason: String get() = reasons.firstOrNull() ?: ""
}

/**
 * Runs the whole chain of section 1 on one candle list and always returns an explainable answer.
 * Gates run in order and the first hard failure wins, so NO TRADE / DATA UNCERTAIN beat any setup.
 * Uses only the candles passed in - callers decide what "now" is - so it is safe inside a walk-forward test.
 */
class AgentAnalyzer(
    private val config: AgentConfig = AgentConfig(),
    private val newsFilter: NewsRiskFilter = NewsRiskFilter(null)
) {
    private val validator = DataValidator(config.candleSeconds * 1000L, config.minCandles)
    /** Middle/higher timeframes follow the entry timeframe the user trades (section 4), not a fixed multiple. */
    private val strategyLibrary = fullStrategyLibrary(
        if (config.middleSeconds > 0 || config.higherSeconds > 0) {
            TimeframePlan(config.candleSeconds, config.middleSeconds.takeIf { it > 0 }, config.higherSeconds.takeIf { it > 0 })
        } else {
            TimeframePlan.forEntry(config.candleSeconds)
        }
    ).filter { it.name !in config.disabledStrategies }

    fun analyze(
        candles: List<Candle>,
        nowMs: Long? = null,
        ocrConfidence: Double? = null,
        evidence: HistoricalEvidence? = null,
        weights: Map<String, Double> = emptyMap(),
        riskPausedReason: String? = null
    ): AgentReport {
        val time = nowMs ?: candles.lastOrNull()?.openTimeMs ?: 0L
        val validation = validator.validate(candles, nowMs, ocrConfidence)
        val session = SessionClassifier.at(time)
        val (news, newsWhy) = newsFilter.assess(time)

        if (!validation.tradable) {
            val insufficient = validation.issues.any { it.code == "TOO_FEW" }
            val head = if (insufficient) "INSUFFICIENT DATA" else "DATA UNCERTAIN"
            return blank(
                AgentStatus.DATA_UNCERTAIN, validation, session, news, time,
                listOf("$head - ${validation.summary}"),
                listOf(AgentCheck("Data quality", false, validation.summary))
            )
        }

        val dqScore = SetupScorer.dataQualityScore(validation.quality.name)
        if (dqScore < config.minDataQualityScore) {
            return blank(
                AgentStatus.DATA_UNCERTAIN, validation, session, news, time,
                listOf("DATA QUALITY TOO LOW ($dqScore/100 < ${config.minDataQualityScore}) - analysis paused. ${validation.summary}"),
                listOf(AgentCheck("Data quality", false, "score $dqScore/100 below minimum ${config.minDataQualityScore}"))
            )
        }

        val series = PriceSeries.window(candles, candles.size, config.modelCandleCap)
        val i = series.size - 1
        val trend = MarketStructure.trendLabel(series)
        val structure = MarketStructure.structureLabel(MarketStructure.swings(series))
        val volatility = MarketStructure.volatilityLabel(series)
        val regime = RegimeClassifier.classify(series)
        val atr = series.atr14[i].takeIf { !it.isNaN() }
        val price = series.closes[i]

        val checks = ArrayList<AgentCheck>()
        checks.add(AgentCheck("Data quality", true, validation.quality.name))
        var entryAssessment: EntryAssessment? = null
        var fake: FakeBreakout? = null
        var score: Int? = null
        val warns = ArrayList<String>()

        fun finish(status: AgentStatus, reasons: List<String>, confluence: ConfluenceResult? = null, edge: EdgeCheck? = null) = AgentReport(
            status = status, direction = QuotexDecision.WAIT, quality = confluence?.quality ?: SetupQuality.NO_SETUP,
            dataQuality = validation.quality, dataSummary = validation.summary, trend = trend, structure = structure,
            volatility = volatility, regime = regime, session = session, newsRisk = news,
            conditionsMet = confluence?.conditionsSatisfied ?: 0, conditionsTotal = confluence?.conditionsTotal ?: 0,
            strategies = emptyList(), checks = checks.toList(), reasons = reasons, edge = edge, timeMs = time,
            lastPrice = price, atr = atr, confluence = confluence,
            setupScore = score, entry = entryAssessment, fakeBreakout = fake, warnings = warns.toList()
        )

        if (riskPausedReason != null) {
            checks.add(AgentCheck("Risk", false, riskPausedReason))
            return finish(AgentStatus.NO_TRADE, listOf("TRADING PAUSED - $riskPausedReason"))
        }
        checks.add(AgentCheck("Risk", true, "within limits"))

        if (news == NewsRisk.HIGH) {
            checks.add(AgentCheck("News", false, newsWhy ?: "high event risk"))
            return finish(AgentStatus.NO_TRADE, listOf("HIGH EVENT RISK - ${newsWhy ?: "major scheduled event nearby"}"))
        }
        checks.add(AgentCheck("News", true, news.name))

        if (regime == MarketRegime.UNSTABLE || volatility == VolatilityState.EXTREME || (config.blockHighVolatility && volatility == VolatilityState.HIGH)) {
            checks.add(AgentCheck("Regime", false, "$regime / $volatility"))
            val why = if (volatility == VolatilityState.EXTREME) "Extreme volatility" else if (volatility == VolatilityState.HIGH) "High volatility (blocked in settings)" else "Unstable market regime"
            return finish(AgentStatus.NO_TRADE, listOf("$why - conditions too erratic to analyse reliably."))
        }
        checks.add(AgentCheck("Regime", true, regime.name))

        val allowed = strategyLibrary.filter { RegimeGate.allows(it.name, regime) }
        val confluence = ConfluenceEngine(allowed, weights).evaluate(series, trend, structure, volatility)
        val leaning = confluence.strategyResults.filter { it.direction != QuotexDecision.WAIT }
        val calls = leaning.count { it.direction == QuotexDecision.CALL }
        val puts = leaning.count { it.direction == QuotexDecision.PUT }

        if (calls >= 2 && puts >= 2) {
            checks.add(AgentCheck("Conflicts", false, "$calls CALL vs $puts PUT"))
            return finish(AgentStatus.WAIT, listOf("CONFLICTING SIGNALS - $calls strategies lean CALL, $puts lean PUT."), confluence)
        }
        checks.add(AgentCheck("Conflicts", true, "$calls CALL / $puts PUT"))

        val dir = confluence.direction
        if (dir == QuotexDecision.WAIT || confluence.quality == SetupQuality.NO_SETUP || confluence.quality == SetupQuality.WEAK_SETUP) {
            return finish(AgentStatus.WAIT, listOf(confluence.reason), confluence)
        }

        // Higher/lower timeframe disagreement is a hard WAIT.
        val mtf = confluence.strategyResults.firstOrNull { it.strategyName == "Multi-Timeframe Confluence" }
        val mtfOk = mtf == null || mtf.direction == QuotexDecision.WAIT || mtf.direction == dir
        checks.add(AgentCheck("Multi-timeframe", mtfOk, mtf?.direction?.name ?: "not enough history"))
        if (!mtfOk) return finish(AgentStatus.WAIT, listOf("Higher and lower timeframe disagree."), confluence)

        // A fresh structural event against the setup (change of character, false breakout, liquidity sweep) is a veto.
        val structureReport = StructureEvents.analyze(series)
        val againstEvents = StructureEvents.against(structureReport, callSide = dir == QuotexDecision.CALL)
        checks.add(
            AgentCheck(
                "Structure events", againstEvents.isEmpty(),
                if (againstEvents.isEmpty()) "no fresh event against the setup" else againstEvents.joinToString { it.type.label }
            )
        )
        if (againstEvents.isNotEmpty()) {
            return finish(AgentStatus.WAIT, listOf("Fresh structure event against the setup: ${againstEvents.joinToString { it.type.label }}."), confluence)
        }

        // Never enter straight into a strong opposing level.
        val intoLevel = atr != null && opposingLevelNear(series, dir, atr)
        checks.add(AgentCheck("Level", !intoLevel, if (intoLevel) "opposing swing level within ${config.opposingLevelAtr} ATR" else "clear"))
        if (intoLevel) return finish(AgentStatus.WAIT, listOf("Entry would run directly into an opposing support/resistance level."), confluence)

        // Late entry / chasing: price already far from EMA21.
        val e21 = series.ema21[i]
        val chasing = atr != null && !e21.isNaN() && abs(price - e21) > atr * 2.5
        checks.add(AgentCheck("Timing", !chasing, if (chasing) "price stretched from EMA21" else "not stretched"))
        if (chasing) return finish(AgentStatus.WAIT, listOf("Price is stretched far from its average - chasing a late entry."), confluence)

        // Fake breakout / breakdown against the setup is a veto (bull trap for CALL, bear trap for PUT).
        val fk = FakeBreakoutDetector.detect(candles.takeLast(config.modelCandleCap))
        fake = fk
        val fakeAgainst = (dir == QuotexDecision.CALL && fk.kind == FakeBreakKind.FAKE_BREAKOUT_UP) ||
            (dir == QuotexDecision.PUT && fk.kind == FakeBreakKind.FAKE_BREAKDOWN)
        checks.add(AgentCheck("Fake breakout", !(fakeAgainst && fk.quality >= 60), if (fakeAgainst) fk.detail else "none against the setup"))
        if (fakeAgainst && fk.quality >= 60) {
            return finish(AgentStatus.WAIT, listOf("Possible fake ${if (dir == QuotexDecision.CALL) "breakout" else "breakdown"} - ${fk.detail}."), confluence)
        }
        if (fakeAgainst) warns.add("weak fake-breakout signs: ${fk.detail}")

        // Entry quality: a strong setup on a late or invalid entry is still WAIT.
        val callSide = dir == QuotexDecision.CALL
        val swingList = MarketStructure.swings(series).filter { it.index < i }
        val startSwing = swingList.lastOrNull { it.isHigh != callSide } // CALL: last swing low; PUT: last swing high
        if (atr != null && startSwing != null) {
            val need = kotlin.math.ceil(confluence.conditionsTotal * config.minConditionsFraction).toInt().coerceAtLeast(1)
            val ea = EntryQualityEvaluator.evaluate(callSide, price, startSwing.price, startSwing.price, atr, confluence.conditionsSatisfied, need)
            entryAssessment = ea
            checks.add(AgentCheck("Entry quality", ea.tradable || ea.quality == EntryQuality.FORMING || ea.quality == EntryQuality.EARLY, "${ea.quality}: ${ea.reason}"))
            if (ea.quality == EntryQuality.LATE || ea.quality == EntryQuality.INVALID) {
                score = SetupScorer.score(ScoreInput(0.0, 0.0, null, SetupScorer.dataQualityScore(validation.quality.name), null, 0, ea.quality))
                return finish(AgentStatus.WAIT, listOf("Entry quality ${ea.quality}: ${ea.reason}."), confluence)
            }
        } else {
            warns.add("entry quality could not be assessed (no recent swing or ATR)")
        }

        val fraction = if (confluence.conditionsTotal == 0) 0.0 else confluence.conditionsSatisfied.toDouble() / confluence.conditionsTotal
        val enough = fraction >= config.minConditionsFraction
        val tookSide = leaning.size
        val agree = if (tookSide == 0) 0.0 else leaning.count { it.direction == dir }.toDouble() / tookSide
        val patternQ = PriceActionEngine.detect(series).filter {
            (callSide && it.bias == PatternBias.BULLISH) || (!callSide && it.bias == PatternBias.BEARISH)
        }.let { if (it.isEmpty()) null else 70 }
        if (nearEdgeWarning(series, dir, atr)) warns.add("opposing level within ${config.opposingLevelAtr * 2} ATR")
        score = SetupScorer.score(
            ScoreInput(fraction, agree, if (mtf == null || mtf.direction == QuotexDecision.WAIT) null else true,
                SetupScorer.dataQualityScore(validation.quality.name), patternQ, warns.size, entryAssessment?.quality)
        )
        checks.add(AgentCheck("Confirmation", enough, "${confluence.conditionsSatisfied} / ${confluence.conditionsTotal} conditions"))

        val belowScore = (score ?: 0) < config.minSetupScore
        checks.add(AgentCheck("Setup score", !belowScore, "${score ?: 0}/100 (minimum ${config.minSetupScore})"))
        if (belowScore) {
            return finish(AgentStatus.WATCH, listOf("Setup score ${score ?: 0}/100 is below the minimum ${config.minSetupScore}."), confluence).withDirection(dir, leaning)
        }

        if (confluence.quality == SetupQuality.WATCH || !enough) {
            return finish(AgentStatus.WATCH, listOf("Setup forming: ${confluence.reason}"), confluence).withDirection(dir, leaning)
        }

        val edge = EdgeTest.check(evidence, config.breakEven, config.edgeMinSamples, config.edgeZThreshold)
        checks.add(AgentCheck("Historical performance", edge.verified || !config.requireVerifiedEdge, edge.summary))
        if (config.requireVerifiedEdge && !edge.verified) {
            return finish(AgentStatus.WATCH, listOf("Setup present but no statistically verified edge yet. ${edge.summary}"), confluence, edge)
                .withDirection(dir, leaning)
        }

        return AgentReport(
            status = AgentStatus.SETUP_DETECTED, direction = dir, quality = confluence.quality,
            dataQuality = validation.quality, dataSummary = validation.summary, trend = trend, structure = structure,
            volatility = volatility, regime = regime, session = session, newsRisk = news,
            conditionsMet = confluence.conditionsSatisfied, conditionsTotal = confluence.conditionsTotal,
            strategies = leaning.filter { it.direction == dir }.map { it.strategyName },
            checks = checks.toList(), reasons = listOf(confluence.reason), edge = edge, timeMs = time,
            lastPrice = price, atr = atr, confluence = confluence,
            setupScore = score, entry = entryAssessment, fakeBreakout = fake, warnings = warns.toList()
        )
    }

    private fun AgentReport.withDirection(dir: QuotexDecision, leaning: List<StrategyResult>): AgentReport =
        copy(direction = dir, strategies = leaning.filter { it.direction == dir }.map { it.strategyName })

    private fun nearEdgeWarning(series: PriceSeries, dir: QuotexDecision, atr: Double?): Boolean {
        if (atr == null) return false
        val i = series.size - 1
        val price = series.closes[i]
        val wide = config.opposingLevelAtr * 2
        val swings = MarketStructure.swings(series)
        return if (dir == QuotexDecision.CALL) swings.any { it.isHigh && it.index < i && it.price > price && it.price - price < atr * wide }
        else swings.any { !it.isHigh && it.index < i && it.price < price && price - it.price < atr * wide }
    }

    private fun opposingLevelNear(series: PriceSeries, dir: QuotexDecision, atr: Double): Boolean {
        val i = series.size - 1
        val price = series.closes[i]
        val swings = MarketStructure.swings(series)
        return if (dir == QuotexDecision.CALL) {
            swings.any { it.isHigh && it.index < i && it.price > price && it.price - price < atr * config.opposingLevelAtr }
        } else {
            swings.any { !it.isHigh && it.index < i && it.price < price && price - it.price < atr * config.opposingLevelAtr }
        }
    }

    private fun blank(
        status: AgentStatus, v: DataValidationReport, session: MarketSession, news: NewsRisk, time: Long,
        reasons: List<String>, checks: List<AgentCheck>
    ) = AgentReport(
        status = status, direction = QuotexDecision.WAIT, quality = SetupQuality.NO_SETUP, dataQuality = v.quality,
        dataSummary = v.summary, trend = TrendState.UNSTABLE, structure = StructureLabel.UNCLEAR,
        volatility = VolatilityState.UNKNOWN, regime = MarketRegime.UNSTABLE, session = session, newsRisk = news,
        conditionsMet = 0, conditionsTotal = 0, strategies = emptyList(), checks = checks, reasons = reasons,
        edge = null, timeMs = time, lastPrice = null, atr = null
    )
}
