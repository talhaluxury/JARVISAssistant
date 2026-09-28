package com.jarvis.assistant.trading

/**
 * PHASE 4 — RISK MANAGEMENT ENGINE (spec §8, §9, §32, §34, §38)
 *
 * "Risk management must override the signal engine... never allow the AI model to override hard
 * risk limits" — this engine takes a [TradeSignal] that [SignalConfidenceEngine] already scored
 * as a *_SETUP and re-checks everything account/exposure-related from scratch. It does not trust
 * or re-derive anything about setup quality (confidence itself), but it DOES re-check R:R as a
 * defensive backstop, because this is the last gate before a real order and spec §32 says every
 * critical dependency must fail closed rather than assume an upstream engine got it right.
 *
 * Every check below returns [RiskVerdict.Blocked] with a specific, journalable reason on failure
 * — there is deliberately no path that logs a vague "risk check failed." An [RiskVerdict.Approved]
 * carries the actual [OrderRequest] to submit; nothing else in this codebase is allowed to build
 * an OrderRequest for a live/demo signal-derived trade.
 *
 * NOT this engine's job: idempotent duplicate-order protection at the broker level (that's
 * [BrokerAdapter.submitOrder]'s clientOrderId check, Phase 1) or position monitoring after entry
 * (a later phase). This engine only answers "should this NEW trade be allowed to exist at all."
 */

/** Exposure bookkeeping deliberately stays percent-of-equity, matching every other [RiskSettings]
 * field, rather than mixing in raw money/pip-value math here — that keeps this engine readable
 * and testable independent of [PositionSizingEngine]'s pip-value assumptions. Wiring real broker
 * positions into this shape (looking up how much was originally risked on each open trade from
 * the trade journal) is a later-phase integration concern, not something this engine computes. */
data class OpenRiskPosition(val pair: CurrencyPair, val direction: TradeDirection, val riskPercentOfEquity: Double)

sealed class RiskVerdict {
    data class Approved(val orderRequest: OrderRequest, val riskPercentOfEquity: Double, val riskAmount: Double) : RiskVerdict()
    data class Blocked(val reason: String) : RiskVerdict()
}

class RiskManagementEngine {

    fun evaluate(
        signal: TradeSignal,
        account: AccountSnapshot,
        currentPrice: Double,
        openPositions: List<OpenRiskPosition>,
        lockState: TradingLockState,
        dailyLossPercentSoFar: Double,
        settings: TradingSettings,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): RiskVerdict {
        val direction = signal.decision.direction
        if (!signal.decision.isActionable || direction == null) {
            return RiskVerdict.Blocked("Signal for ${signal.pair} is ${signal.decision}, not an actionable setup — nothing to risk-check.")
        }

        // Locks first (spec §38: "do not let the AI override this lock") — everything else is
        // moot if trading is currently locked at all.
        if (!lockState.isTradingAllowed(nowEpochMillis)) {
            return RiskVerdict.Blocked(lockState.lockReason ?: "Trading is currently locked.")
        }

        // Defensive backstop alongside EmergencyStopController's own daily-loss lock — this
        // engine should never approve a trade that breaches the configured daily limit even if
        // the lock controller somehow wasn't updated yet.
        if (dailyLossPercentSoFar >= settings.risk.maxDailyLossPercent) {
            return RiskVerdict.Blocked(
                "Daily loss limit of ${settings.risk.maxDailyLossPercent}% reached (currently ${"%.2f".format(dailyLossPercentSoFar)}%)."
            )
        }

        val entry = signal.entry
        val stopLoss = signal.stopLoss
        val takeProfit = signal.takeProfit
        if (entry == null || stopLoss == null || takeProfit == null) {
            return RiskVerdict.Blocked("Stop-loss/take-profit could not be determined for ${signal.pair} — refusing to size a trade with no defined risk.")
        }

        val rr = signal.riskRewardRatio
        if (rr == null || rr < settings.risk.minRiskRewardRatio) {
            return RiskVerdict.Blocked(
                "Risk/reward (${rr?.let { "%.2f".format(it) } ?: "unavailable"}) is below the configured minimum of ${settings.risk.minRiskRewardRatio}."
            )
        }

        if (openPositions.size >= settings.risk.maxOpenTrades) {
            return RiskVerdict.Blocked("Maximum open trades (${settings.risk.maxOpenTrades}) already reached.")
        }

        // Pair-level duplicate protection (spec §14) at the risk-decision layer — the broker
        // adapter's clientOrderId check (Phase 1) guards against re-submitting the exact same
        // signal; this guards against opening a second, different signal on a pair we're already
        // in, which the broker-level check alone would not catch.
        if (openPositions.any { it.pair == signal.pair }) {
            return RiskVerdict.Blocked("A position on ${signal.pair} is already open — duplicate-signal protection.")
        }

        val proposedRiskPercent = settings.risk.maxRiskPerTradePercent
        for (currency in setOf(signal.pair.base, signal.pair.quote)) {
            val existingExposure = openPositions
                .filter { it.pair.base == currency || it.pair.quote == currency }
                .sumOf { it.riskPercentOfEquity }
            val projectedExposure = existingExposure + proposedRiskPercent
            if (projectedExposure > settings.risk.maxExposurePerCurrencyPercent) {
                return RiskVerdict.Blocked(
                    "Adding this trade would push $currency exposure to ${"%.2f".format(projectedExposure)}%, " +
                        "above the configured limit of ${settings.risk.maxExposurePerCurrencyPercent}%."
                )
            }
        }

        val sizing = PositionSizingEngine.calculate(
            accountEquity = account.equity,
            riskPercent = proposedRiskPercent,
            entry = entry,
            stopLoss = stopLoss,
            pair = signal.pair,
            currentPrice = currentPrice,
            maxLotSize = settings.risk.maxLotSize
        ) ?: return RiskVerdict.Blocked("Position size could not be calculated for ${signal.pair} (unsupported pair/price for pip-value conversion, or zero stop distance).")

        if (sizing.lotSize <= 0.0) {
            return RiskVerdict.Blocked("Calculated position size rounds to zero at the configured risk level — account equity is too small for this stop distance.")
        }

        val requiredMarginEstimate = sizing.lotSize * ASSUMED_CONTRACT_SIZE * currentPrice / ASSUMED_LEVERAGE
        if (account.marginAvailable < requiredMarginEstimate) {
            return RiskVerdict.Blocked(
                "Insufficient margin: estimated requirement ${"%.2f".format(requiredMarginEstimate)}, available ${"%.2f".format(account.marginAvailable)}."
            )
        }

        val order = OrderRequest(
            clientOrderId = signal.id,
            pair = signal.pair,
            direction = direction,
            lotSize = sizing.lotSize,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            entryType = EntryType.MARKET
        )
        val actualRiskPercent = (sizing.riskAmount / account.equity) * 100.0
        return RiskVerdict.Approved(order, actualRiskPercent, sizing.riskAmount)
    }

    private companion object {
        // Both documented simplifications (see PositionSizingEngine) — a real broker integration
        // should supply actual contract size and account leverage rather than these assumptions.
        const val ASSUMED_CONTRACT_SIZE = 100_000.0
        const val ASSUMED_LEVERAGE = 30.0
    }
}
