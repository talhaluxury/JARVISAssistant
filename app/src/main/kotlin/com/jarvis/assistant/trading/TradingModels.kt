package com.jarvis.assistant.trading

import kotlinx.serialization.Serializable

/**
 * PHASE 1 — CORE DOMAIN MODELS (Forex)
 *
 * Deliberately dumb data classes with no logic. Every later engine (structure, confluence,
 * risk, execution) consumes these — keeping them free of behavior means they can be unit
 * tested, serialized for the trade journal, and passed across coroutine/Flow boundaries
 * without surprises.
 *
 * Quotex has its own, separate model set (see QuotexModels.kt) — the spec is explicit that
 * Forex and Quotex must not share execution assumptions, so we don't force a shared hierarchy
 * just to save a few lines.
 */

/** Watchlist is intentionally closed/enum-like rather than a free-text symbol, per spec §25
 * ("do not monitor unlimited instruments unnecessarily"). Add pairs here deliberately. */
@Serializable
enum class CurrencyPair(val label: String, val base: String, val quote: String) {
    EURUSD("EUR/USD", "EUR", "USD"),
    GBPUSD("GBP/USD", "GBP", "USD"),
    USDJPY("USD/JPY", "USD", "JPY"),
    USDCHF("USD/CHF", "USD", "CHF"),
    AUDUSD("AUD/USD", "AUD", "USD"),
    USDCAD("USD/CAD", "USD", "CAD"),
    NZDUSD("NZD/USD", "NZD", "USD"),
    XAUUSD("XAU/USD", "XAU", "USD");

    /** JPY and metals quote in different decimal conventions — every pip/ATR/SL calculation
     * downstream needs this, so it lives on the enum rather than being re-derived ad hoc. */
    val pipDecimalPlaces: Int get() = when (this) {
        USDJPY -> 2
        XAUUSD -> 2
        else -> 4
    }

    val pipSize: Double get() = when (pipDecimalPlaces) {
        2 -> 0.01
        else -> 0.0001
    }
}

@Serializable
enum class Timeframe(val minutes: Int, val label: String) {
    M1(1, "1M"), M5(5, "5M"), M15(15, "15M"), H1(60, "1H"), H4(240, "4H"), D1(1440, "1D");

    companion object {
        /** Spec §3 default confluence stack: 1D macro -> 4H structure -> 1H trend -> 15M setup
         * -> 5M entry. Kept as an explicit ordered list (not "all values") so the intent —
         * higher timeframe for context, lower for entry — is visible at the call site. */
        val DEFAULT_CONFLUENCE_STACK = listOf(D1, H4, H1, M15, M5)
    }
}

/** One OHLC candle. `source` and `isStale` travel with the data itself (spec §20/21: every
 * price update must carry provenance and freshness — never inferred later from a timestamp
 * diff at the point of use, where it's easy to forget). */
data class Candle(
    val pair: CurrencyPair,
    val timeframe: Timeframe,
    val openTimeEpochMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double? = null,
    val source: DataSource,
    val isStale: Boolean = false
) {
    init {
        require(high >= low) { "Candle high ($high) below low ($low) for $pair ${timeframe.label}" }
        require(high >= open && high >= close) { "Candle high inconsistent with open/close for $pair" }
        require(low <= open && low <= close) { "Candle low inconsistent with open/close for $pair" }
    }
}

/** Live bid/ask snapshot — separate from Candle because spread/staleness checks (spec §7, §9)
 * happen on ticks, not on completed candles. */
data class PriceTick(
    val pair: CurrencyPair,
    val bid: Double,
    val ask: Double,
    val timestampEpochMillis: Long,
    val source: DataSource
) {
    val spread: Double get() = ask - bid
    val mid: Double get() = (bid + ask) / 2.0
    fun spreadInPips(): Double = spread / pair.pipSize
}

enum class DataSource { DEMO_SIMULATED, BROKER_LIVE, BROKER_HISTORICAL }

/** Data-quality verdict, produced by validation (Phase 1 stub here; full rules land with the
 * MarketDataService validation logic in a later phase). Kept as its own sealed type rather than
 * a boolean so a rejection always carries a human-readable reason for the trade journal / HUD —
 * spec §21 "never trade on corrupted data" plus §23 "explain every decision". */
sealed class DataQuality {
    object Valid : DataQuality()
    data class Invalid(val reason: String) : DataQuality()
}

/** The exhaustive Forex decision set from spec §2. Deliberately NOT a plain BUY/SELL/WAIT enum —
 * NO_TRADE, MARKET_UNAVAILABLE and RISK_BLOCKED are distinct outcomes with distinct causes, and
 * collapsing them would make "why didn't it trade" unanswerable from the journal alone. */
enum class TradeDecision {
    STRONG_BUY_SETUP,
    BUY_SETUP,
    WAIT,
    STRONG_SELL_SETUP,
    SELL_SETUP,
    NO_TRADE,
    MARKET_UNAVAILABLE,
    RISK_BLOCKED;

    val isActionable: Boolean get() = this == STRONG_BUY_SETUP || this == BUY_SETUP ||
        this == STRONG_SELL_SETUP || this == SELL_SETUP

    val direction: TradeDirection? get() = when (this) {
        STRONG_BUY_SETUP, BUY_SETUP -> TradeDirection.BUY
        STRONG_SELL_SETUP, SELL_SETUP -> TradeDirection.SELL
        else -> null
    }
}

enum class TradeDirection { BUY, SELL }

@Serializable
enum class TradingMode { DEMO, LIVE }

/** A single confluence-engine component result (spec §9: "PASS/FAIL/NEUTRAL, not an average").
 * `critical = true` means a FAIL here forces NO_TRADE regardless of the aggregate score —
 * enforced by the confluence engine in a later phase, not by this data class, but the flag has
 * to live on the model so that engine has something to check. */
enum class CheckResult { PASS, FAIL, NEUTRAL }

data class ConfirmationCheck(
    val name: String,
    val result: CheckResult,
    val critical: Boolean,
    val detail: String
)

/** Everything needed to explain a decision after the fact without re-running the pipeline —
 * this IS the trade-journal record shape for a signal (spec §22/23), whether or not it ever
 * became an order. */
data class TradeSignal(
    val id: String,
    val pair: CurrencyPair,
    val decision: TradeDecision,
    val confidenceScore: Int, // 0-100, confluence strength — NEVER "probability of profit" (spec §6)
    val timeframesAnalyzed: List<Timeframe>,
    val checks: List<ConfirmationCheck>,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskRewardRatio: Double? = null,
    val reason: String,
    val generatedAtEpochMillis: Long
) {
    init {
        require(confidenceScore in 0..100) { "confidenceScore must be 0-100, was $confidenceScore" }
    }
}
