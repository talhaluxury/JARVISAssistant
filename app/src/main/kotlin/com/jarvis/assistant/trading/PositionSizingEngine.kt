package com.jarvis.assistant.trading

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * PHASE 4 — POSITION SIZING ENGINE (spec §8: "position sizing based on account equity + risk
 * percentage + stop-loss distance + instrument characteristics... never a fixed lot size")
 *
 * Deliberate simplifications, both called out explicitly rather than silently baked in:
 *  - Assumes a 100,000-unit standard lot and an account currency of USD. A real multi-currency
 *    account (or a broker quoting mini/micro lots differently) needs this parameterized —
 *    tracked as a follow-up, not pretended away.
 *  - Pip value conversion only handles the two shapes our [CurrencyPair] enum has: quote == USD
 *    (EURUSD, GBPUSD, AUDUSD, NZDUSD, XAUUSD) where pip value in USD is independent of price,
 *    and quote != USD (USDJPY, USDCHF, USDCAD) where it's divided by the current USD/quote rate.
 *    A pair where NEITHER currency is USD (e.g. EURGBP) isn't representable by this engine yet —
 *    [pipValuePerLotInUsd] returns null for that case rather than silently guessing, and callers
 *    must treat null as "cannot size this trade," never as "assume some default."
 *
 * Lot size is always rounded DOWN to the nearest [lotStep] — spec's "never simply use a fixed
 * lot size" cuts both ways: this must never round up and quietly risk more than configured.
 */
object PositionSizingEngine {

    private const val CONTRACT_SIZE_PER_LOT = 100_000.0

    /** Null return means "cannot compute a USD pip value for this pair/price" — see class doc. */
    fun pipValuePerLotInUsd(pair: CurrencyPair, currentPrice: Double): Double? {
        if (pair.quote == "USD") return pair.pipSize * CONTRACT_SIZE_PER_LOT
        if (pair.base == "USD" && currentPrice > 0) return (pair.pipSize * CONTRACT_SIZE_PER_LOT) / currentPrice
        return null // neither leg is USD — not supported by this simplified engine yet
    }

    data class SizingResult(
        val lotSize: Double,
        val riskAmount: Double,
        val pipValuePerLot: Double,
        val stopLossPips: Double
    )

    /**
     * Returns null whenever a safe lot size cannot be determined at all (equity/risk non-positive,
     * zero stop distance, unsupported pair, or the resulting size rounds down to zero) — callers
     * (the risk engine) must treat null the same as any other "cannot size this trade" block,
     * per spec §9's "stop-loss cannot be determined" -> no trade.
     */
    fun calculate(
        accountEquity: Double,
        riskPercent: Double,
        entry: Double,
        stopLoss: Double,
        pair: CurrencyPair,
        currentPrice: Double,
        maxLotSize: Double,
        lotStep: Double = 0.01
    ): SizingResult? {
        if (accountEquity <= 0 || riskPercent <= 0 || maxLotSize <= 0) return null
        val stopDistance = abs(entry - stopLoss)
        if (stopDistance <= 0) return null
        val stopLossPips = stopDistance / pair.pipSize
        val pipValue = pipValuePerLotInUsd(pair, currentPrice) ?: return null
        if (pipValue <= 0) return null

        val riskAmount = accountEquity * riskPercent / 100.0
        val rawLotSize = riskAmount / (stopLossPips * pipValue)
        // Tiny epsilon guards against floating-point noise (e.g. 0.1 / 0.01 evaluating to
        // 9.999999999998 in IEEE 754 double) rounding a clean lot size down a whole step.
        val steppedLotSize = floor(rawLotSize / lotStep + 1e-9) * lotStep
        val cappedLotSize = min(steppedLotSize, maxLotSize)
        if (cappedLotSize <= 0.0) return null

        val actualRiskAmount = cappedLotSize * stopLossPips * pipValue
        return SizingResult(cappedLotSize, actualRiskAmount, pipValue, stopLossPips)
    }
}
