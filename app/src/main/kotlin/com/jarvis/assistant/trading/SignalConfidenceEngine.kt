package com.jarvis.assistant.trading

import kotlin.math.abs

/**
 * PHASE 3 — SIGNAL CONFIDENCE ENGINE (spec §6, §9, §35 up through "CONFLUENCE SCORING")
 *
 * This is the only place a [TradeDecision] gets produced from analysis. It is explicitly NOT
 * the risk engine: [RiskSettings.minRiskRewardRatio] and [RiskSettings.maxSpreadPips] are read
 * here only to score/gate the *setup quality*, not to check account-level exposure, daily loss
 * locks, or position sizing — that's the RiskManagementEngine's job in a later phase, and per
 * spec §34 it has veto authority over whatever this engine outputs. A *_SETUP decision from here
 * is a candidate, not an authorization to trade.
 *
 * Scoring model (spec §9: "PASS/FAIL/NEUTRAL, not a blind average"): every [ConfirmationCheck]
 * contributes its weight on PASS, half its weight on NEUTRAL, and zero on FAIL — but any FAIL on
 * a check marked `critical` short-circuits straight to NO_TRADE regardless of the aggregate
 * score, matching spec §9's own examples ("strong trend + bad entry location = NO-TRADE").
 */
class SignalConfidenceEngine {

    private data class Weighted(val check: ConfirmationCheck, val weight: Double)

    /**
     * @param entryCandles candles for the lowest (entry) timeframe in the confluence stack —
     *   used for indicators and for the entry-timeframe structure event lookup.
     * @param entryStructure structure snapshot for that same timeframe.
     * @param mtfSnapshots the full per-timeframe stack (spec §3's confluence stack), used only
     *   for alignment — this engine does not re-derive structure itself.
     * @param tickQuality result of validating the live tick BEFORE calling this engine
     *   (spec §21) — passed in rather than re-checked here so data validation stays owned by
     *   [MarketDataService] alone.
     */
    fun generate(
        pair: CurrencyPair,
        mtfSnapshots: List<TimeframeStructureSnapshot>,
        entryCandles: List<Candle>,
        entryStructure: MarketStructureSnapshot,
        latestTick: PriceTick,
        tickQuality: DataQuality,
        settings: TradingSettings,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): TradeSignal {
        if (tickQuality is DataQuality.Invalid) {
            return unavailable(pair, mtfSnapshots.map { it.timeframe }, tickQuality.reason, nowEpochMillis)
        }
        if (entryCandles.size < 30) {
            return unavailable(pair, mtfSnapshots.map { it.timeframe }, "Insufficient candle history for $pair to analyze.", nowEpochMillis)
        }

        val alignment = computeAlignment(mtfSnapshots)
        val timeframes = mtfSnapshots.map { it.timeframe }

        val bias: TradeDirection? = when (alignment) {
            MultiTimeframeAlignment.ALIGNED_BULLISH -> TradeDirection.BUY
            MultiTimeframeAlignment.ALIGNED_BEARISH -> TradeDirection.SELL
            MultiTimeframeAlignment.CONFLICTING -> null
            MultiTimeframeAlignment.NO_CLEAR_DIRECTION -> null
        }

        if (alignment == MultiTimeframeAlignment.CONFLICTING) {
            return TradeSignal(
                id = signalId(pair, nowEpochMillis), pair = pair, decision = TradeDecision.NO_TRADE,
                confidenceScore = 0, timeframesAnalyzed = timeframes, checks = emptyList(),
                reason = "Timeframes disagree on direction: ${describeDirections(mtfSnapshots)}.",
                generatedAtEpochMillis = nowEpochMillis
            )
        }
        if (bias == null) {
            return TradeSignal(
                id = signalId(pair, nowEpochMillis), pair = pair, decision = TradeDecision.WAIT,
                confidenceScore = 0, timeframesAnalyzed = timeframes, checks = emptyList(),
                reason = "No clear directional bias across ${timeframes.joinToString { it.label }}.",
                generatedAtEpochMillis = nowEpochMillis
            )
        }

        val closes = entryCandles.map { it.close }
        val rsi = TechnicalIndicators.rsi(closes).lastOrNull()
        val macd = TechnicalIndicators.macd(closes)
        val macdHistogram = macd.histogram.lastOrNull()
        val atr = TechnicalIndicators.atr(entryCandles).lastOrNull()
        val historicalAtr = TechnicalIndicators.atr(entryCandles.dropLast(minOf(20, entryCandles.size / 2))).filterNotNull()

        val checks = mutableListOf<Weighted>()

        // 1. Trend alignment across the confluence stack.
        checks += Weighted(
            ConfirmationCheck(
                "Trend alignment", CheckResult.PASS, critical = true,
                "${describeDirections(mtfSnapshots)} all agree on ${bias.name}."
            ),
            weight = 1.5
        )

        // 2. Momentum: RSI + MACD histogram must not contradict the bias.
        checks += weightedMomentumCheck(bias, rsi, macdHistogram)

        // 3. Market structure on the entry timeframe: recent events should support, not oppose, the bias.
        checks += weightedStructureCheck(bias, entryStructure)

        // 4. Support/resistance room: is there enough space to the next opposing level?
        checks += weightedRoomCheck(bias, latestTick.mid, entryStructure, atr)

        // 5. Volatility sanity: current ATR shouldn't be wildly abnormal vs recent history.
        checks += weightedVolatilityCheck(atr, historicalAtr)

        // 6. Multi-timeframe confirmation (redundant with #1 by construction here, but kept as
        //    its own line item per spec §6's explicit category list, for journal transparency).
        checks += Weighted(
            ConfirmationCheck("Multi-timeframe confirmation", CheckResult.PASS, critical = false, "Confluence stack agrees."),
            weight = 1.0
        )

        // 7. Entry-timeframe confirmation: does the entry candle's own structure event support entering now?
        checks += weightedEntryConfirmationCheck(bias, entryStructure)

        // 8. Risk/reward using a structure-derived candidate stop/target.
        val levels = estimateLevels(bias, latestTick.mid, atr, entryStructure)
        checks += weightedRiskRewardCheck(levels, settings.risk.minRiskRewardRatio)

        // 9. Spread.
        checks += Weighted(
            spreadCheck(latestTick, settings.risk.maxSpreadPips),
            weight = 1.0
        )

        // 10. Overall market conditions catch-all (kept simple in Phase 3: entry-timeframe regime
        //     itself shouldn't be UNCERTAIN, even if the higher timeframes are trending).
        checks += Weighted(
            ConfirmationCheck(
                "Market conditions", if (entryStructure.regime == MarketRegime.UNCERTAIN) CheckResult.NEUTRAL else CheckResult.PASS,
                critical = false,
                "Entry timeframe regime: ${entryStructure.regime}."
            ),
            weight = 0.75
        )

        val criticalFailure = checks.firstOrNull { it.check.critical && it.check.result == CheckResult.FAIL }
        val confidence = score(checks)
        val rr = levels?.let { triple -> abs(triple.third - triple.first) / abs(triple.first - triple.second) }

        if (criticalFailure != null) {
            return TradeSignal(
                id = signalId(pair, nowEpochMillis), pair = pair, decision = TradeDecision.NO_TRADE,
                confidenceScore = confidence, timeframesAnalyzed = timeframes, checks = checks.map { it.check },
                entry = levels?.first, stopLoss = levels?.second, takeProfit = levels?.third,
                riskRewardRatio = rr,
                reason = "NO TRADE — ${criticalFailure.check.name}: ${criticalFailure.check.detail}",
                generatedAtEpochMillis = nowEpochMillis
            )
        }

        val decision = when {
            confidence >= settings.minConfidenceScoreToTrade + 15 ->
                if (bias == TradeDirection.BUY) TradeDecision.STRONG_BUY_SETUP else TradeDecision.STRONG_SELL_SETUP
            confidence >= settings.minConfidenceScoreToTrade ->
                if (bias == TradeDirection.BUY) TradeDecision.BUY_SETUP else TradeDecision.SELL_SETUP
            else -> TradeDecision.WAIT
        }

        return TradeSignal(
            id = signalId(pair, nowEpochMillis),
            pair = pair,
            decision = decision,
            confidenceScore = confidence,
            timeframesAnalyzed = timeframes,
            checks = checks.map { it.check },
            entry = levels?.first,
            stopLoss = levels?.second,
            takeProfit = levels?.third,
            riskRewardRatio = rr,
            reason = explain(decision, bias, confidence, checks.map { it.check }),
            generatedAtEpochMillis = nowEpochMillis
        )
    }

    private fun unavailable(pair: CurrencyPair, timeframes: List<Timeframe>, reason: String, now: Long) = TradeSignal(
        id = signalId(pair, now), pair = pair, decision = TradeDecision.MARKET_UNAVAILABLE,
        confidenceScore = 0, timeframesAnalyzed = timeframes, checks = emptyList(),
        reason = reason, generatedAtEpochMillis = now
    )

    private fun signalId(pair: CurrencyPair, now: Long) = "${pair.name}-$now"

    private fun describeDirections(snapshots: List<TimeframeStructureSnapshot>) =
        snapshots.joinToString { "${it.timeframe.label}:${it.structure.regime}" }

    private fun weightedMomentumCheck(bias: TradeDirection, rsi: Double?, macdHistogram: Double?): Weighted {
        if (rsi == null || macdHistogram == null) {
            return Weighted(ConfirmationCheck("Momentum", CheckResult.NEUTRAL, critical = false, "Insufficient history for RSI/MACD."), 1.0)
        }
        val rsiSupports = if (bias == TradeDirection.BUY) rsi > 50 else rsi < 50
        val macdSupports = if (bias == TradeDirection.BUY) macdHistogram > 0 else macdHistogram < 0
        val result = when {
            rsiSupports && macdSupports -> CheckResult.PASS
            rsiSupports || macdSupports -> CheckResult.NEUTRAL
            else -> CheckResult.FAIL
        }
        return Weighted(
            ConfirmationCheck("Momentum", result, critical = false, "RSI=${"%.1f".format(rsi)}, MACD histogram=${"%.5f".format(macdHistogram)}."),
            weight = 1.25
        )
    }

    private fun weightedStructureCheck(bias: TradeDirection, structure: MarketStructureSnapshot): Weighted {
        val opposing = structure.recentEvents.any {
            (bias == TradeDirection.BUY && it.event == StructureEvent.RESISTANCE_REJECTION) ||
                (bias == TradeDirection.SELL && it.event == StructureEvent.SUPPORT_REJECTION)
        }
        val supporting = structure.recentEvents.any {
            (bias == TradeDirection.BUY && ((it.event == StructureEvent.BREAKOUT && it.level.type == LevelType.RESISTANCE) || it.event == StructureEvent.SUPPORT_REJECTION)) ||
                (bias == TradeDirection.SELL && ((it.event == StructureEvent.BREAKOUT && it.level.type == LevelType.SUPPORT) || it.event == StructureEvent.RESISTANCE_REJECTION))
        }
        val result = when {
            opposing -> CheckResult.FAIL
            supporting -> CheckResult.PASS
            else -> CheckResult.NEUTRAL
        }
        val detail = structure.recentEvents.joinToString { "${it.event}@${it.level.price}" }.ifBlank { "No recent structure events." }
        return Weighted(ConfirmationCheck("Market structure", result, critical = false, detail), weight = 1.5)
    }

    private fun weightedRoomCheck(bias: TradeDirection, currentPrice: Double, structure: MarketStructureSnapshot, atr: Double?): Weighted {
        if (atr == null || atr <= 0) {
            return Weighted(ConfirmationCheck("Support/resistance room", CheckResult.FAIL, critical = true, "ATR unavailable — cannot judge room to next level."), 1.5)
        }
        val nearestOpposing = if (bias == TradeDirection.BUY) {
            structure.resistanceLevels.filter { it.price > currentPrice }.minByOrNull { it.price }
        } else {
            structure.supportLevels.filter { it.price < currentPrice }.maxByOrNull { it.price }
        }
        if (nearestOpposing == null) {
            return Weighted(ConfirmationCheck("Support/resistance room", CheckResult.NEUTRAL, critical = true, "No established opposing level nearby — open room."), 1.5)
        }
        val distanceInAtr = abs(nearestOpposing.price - currentPrice) / atr
        val result = if (distanceInAtr >= 1.0) CheckResult.PASS else CheckResult.FAIL
        return Weighted(
            ConfirmationCheck(
                "Support/resistance room", result, critical = true,
                "Nearest opposing level at ${nearestOpposing.price} is ${"%.2f".format(distanceInAtr)}x ATR away."
            ),
            weight = 1.5
        )
    }

    private fun weightedVolatilityCheck(atr: Double?, historicalAtr: List<Double>): Weighted {
        if (atr == null || historicalAtr.isEmpty()) {
            return Weighted(ConfirmationCheck("Volatility", CheckResult.NEUTRAL, critical = true, "Insufficient history to judge volatility."), 1.0)
        }
        val avgHistorical = historicalAtr.average()
        if (avgHistorical <= 0) {
            return Weighted(ConfirmationCheck("Volatility", CheckResult.NEUTRAL, critical = true, "Historical ATR baseline is zero."), 1.0)
        }
        val ratio = atr / avgHistorical
        val result = if (ratio in 0.3..2.5) CheckResult.PASS else CheckResult.FAIL
        return Weighted(
            ConfirmationCheck("Volatility", result, critical = true, "Current ATR is ${"%.2f".format(ratio)}x the recent average."),
            weight = 1.0
        )
    }

    private fun weightedEntryConfirmationCheck(bias: TradeDirection, structure: MarketStructureSnapshot): Weighted {
        val lastEvent = structure.recentEvents.lastOrNull()
        if (lastEvent == null) {
            return Weighted(ConfirmationCheck("Entry confirmation", CheckResult.NEUTRAL, critical = true, "No entry-timeframe structure event yet."), 1.25)
        }
        val supports = (bias == TradeDirection.BUY && lastEvent.event in setOf(StructureEvent.BREAKOUT, StructureEvent.SUPPORT_REJECTION)) ||
            (bias == TradeDirection.SELL && lastEvent.event in setOf(StructureEvent.BREAKOUT, StructureEvent.RESISTANCE_REJECTION))
        val opposes = (bias == TradeDirection.BUY && lastEvent.event == StructureEvent.RESISTANCE_REJECTION) ||
            (bias == TradeDirection.SELL && lastEvent.event == StructureEvent.SUPPORT_REJECTION)
        val result = when {
            opposes -> CheckResult.FAIL
            supports -> CheckResult.PASS
            else -> CheckResult.NEUTRAL
        }
        return Weighted(ConfirmationCheck("Entry confirmation", result, critical = true, "${lastEvent.event} at ${lastEvent.level.price}."), 1.25)
    }

    /** entry/stopLoss/takeProfit — see file header: this is a PRELIMINARY candidate for scoring
     * and journaling only. Final stop/target placement, trailing, and break-even rules are the
     * dedicated Stop-Loss/Take-Profit engines in a later phase. Returns null if a stop-loss
     * cannot be derived sensibly, which spec §9/§32 treats as an automatic block condition
     * ("stop-loss cannot be determined") — here it simply fails the risk/reward check since no
     * engine downstream of this one can compute anything without it. */
    private fun estimateLevels(bias: TradeDirection, currentPrice: Double, atr: Double?, structure: MarketStructureSnapshot): Triple<Double, Double, Double>? {
        if (atr == null || atr <= 0) return null
        return if (bias == TradeDirection.BUY) {
            val support = structure.supportLevels.filter { it.price < currentPrice }.maxByOrNull { it.price }?.price
                ?: (currentPrice - atr * 1.5)
            val resistance = structure.resistanceLevels.filter { it.price > currentPrice }.minByOrNull { it.price }?.price
                ?: (currentPrice + atr * 3.0)
            val stopLoss = support - atr * 0.2
            if (stopLoss >= currentPrice) null else Triple(currentPrice, stopLoss, resistance)
        } else {
            val resistance = structure.resistanceLevels.filter { it.price > currentPrice }.minByOrNull { it.price }?.price
                ?: (currentPrice + atr * 1.5)
            val support = structure.supportLevels.filter { it.price < currentPrice }.maxByOrNull { it.price }?.price
                ?: (currentPrice - atr * 3.0)
            val stopLoss = resistance + atr * 0.2
            if (stopLoss <= currentPrice) null else Triple(currentPrice, stopLoss, support)
        }
    }

    private fun weightedRiskRewardCheck(levels: Triple<Double, Double, Double>?, minRiskReward: Double): Weighted {
        if (levels == null) {
            return Weighted(ConfirmationCheck("Risk/reward", CheckResult.FAIL, critical = true, "Stop-loss could not be calculated."), 1.5)
        }
        val (entry, stopLoss, takeProfit) = levels
        val risk = abs(entry - stopLoss)
        val reward = abs(takeProfit - entry)
        if (risk <= 0) {
            return Weighted(ConfirmationCheck("Risk/reward", CheckResult.FAIL, critical = true, "Calculated risk is zero."), 1.5)
        }
        val rr = reward / risk
        val result = if (rr >= minRiskReward) CheckResult.PASS else CheckResult.FAIL
        return Weighted(ConfirmationCheck("Risk/reward", result, critical = true, "R:R = 1:${"%.2f".format(rr)} (minimum $minRiskReward)."), 1.5)
    }

    private fun spreadCheck(tick: PriceTick, maxSpreadPips: Double): ConfirmationCheck {
        val spreadPips = tick.spreadInPips()
        val result = if (spreadPips <= maxSpreadPips) CheckResult.PASS else CheckResult.FAIL
        return ConfirmationCheck("Spread", result, critical = true, "${"%.1f".format(spreadPips)} pips (max $maxSpreadPips).")
    }

    private fun score(checks: List<Weighted>): Int {
        val totalWeight = checks.sumOf { it.weight }
        if (totalWeight <= 0) return 0
        val achieved = checks.sumOf {
            when (it.check.result) {
                CheckResult.PASS -> it.weight
                CheckResult.NEUTRAL -> it.weight * 0.5
                CheckResult.FAIL -> 0.0
            }
        }
        return ((achieved / totalWeight) * 100).toInt().coerceIn(0, 100)
    }

    private fun explain(decision: TradeDecision, bias: TradeDirection, confidence: Int, checks: List<ConfirmationCheck>): String {
        val passing = checks.filter { it.result == CheckResult.PASS }.map { it.name }
        val failing = checks.filter { it.result == CheckResult.FAIL }.map { it.name }
        return when (decision) {
            TradeDecision.STRONG_BUY_SETUP, TradeDecision.STRONG_SELL_SETUP ->
                "$bias setup with strong confluence ($confidence/100). Confirmed by: ${passing.joinToString()}."
            TradeDecision.BUY_SETUP, TradeDecision.SELL_SETUP ->
                "$bias setup meets configured criteria ($confidence/100). Confirmed by: ${passing.joinToString()}."
            TradeDecision.WAIT ->
                "Directional bias is $bias but confluence is insufficient ($confidence/100)." +
                    if (failing.isNotEmpty()) " Weak on: ${failing.joinToString()}." else ""
            else -> "Decision: $decision."
        }
    }
}
