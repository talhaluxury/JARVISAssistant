package com.jarvis.assistant.trading

/**
 * PHASE 1 — QUOTEX: ANALYSIS-ONLY BOUNDARY
 *
 * Quotex is kept as its own model set per spec §7 ("do not reuse Forex execution assumptions"),
 * but with one deliberate deviation from the original brief: there is no QuotexOrderRequest,
 * no QuotexBrokerAdapter, and no execution path anywhere in this package.
 *
 * Why: Quotex does not publish an official trading API for third-party clients, and its terms
 * of service prohibit bots/automated trading on retail accounts. An "execution adapter" for it
 * would necessarily mean reverse-engineering a private session protocol to place trades without
 * the platform's authorization — that's not something this codebase implements, regardless of
 * how the request is framed (this mirrors what the spec itself says in its "if no official
 * execution API exists, provide analysis-only" clause — it's just enforced here at the type
 * level instead of left to a runtime check).
 *
 * What IS supported: feeding this engine price data (manually entered, or read from whatever
 * legitimate source the user has — e.g. their own eyes on the Quotex chart) and getting back a
 * CALL/PUT/WAIT/NO_TRADE analysis with reasoning, exactly like the Forex signal engine, for the
 * user to act on manually.
 */

enum class QuotexAsset { EURUSD_OTC, GBPUSD_OTC, XAUUSD, BTCUSD, CUSTOM }

enum class QuotexDecision { CALL, PUT, WAIT, NO_TRADE }

data class QuotexCandle(
    val asset: QuotexAsset,
    val timeframeSeconds: Int, // Quotex charts are typically sub-minute; seconds is the natural unit
    val openTimeEpochMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** Always true here — see file header. Kept explicit (not just "assume manual") so any
     * future data source is forced to declare itself rather than silently defaulting. */
    val manuallyEntered: Boolean = true
)

/** Mirrors [TradeSignal]'s shape but with `expirySeconds` in place of stop-loss/take-profit,
 * and deliberately no fields resembling an order id, fill price, or execution status — there is
 * nothing downstream that could execute this. */
data class QuotexSignal(
    val id: String,
    val asset: QuotexAsset,
    val decision: QuotexDecision,
    val confidenceScore: Int,
    val suggestedExpirySeconds: Int?,
    val checks: List<ConfirmationCheck>,
    val reason: String,
    val generatedAtEpochMillis: Long
) {
    init {
        require(confidenceScore in 0..100) { "confidenceScore must be 0-100, was $confidenceScore" }
    }
}

/** Full scoring/expiry-suitability logic (spec §8/§9/§10) is a later phase; this interface
 * fixes the analysis-only contract now so nothing built against it later can grow an execute()
 * method without visibly changing this file. */
interface QuotexAnalysisEngine {
    fun analyze(asset: QuotexAsset, candles: List<QuotexCandle>): QuotexSignal
}
