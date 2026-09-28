package com.jarvis.assistant.trading

import kotlin.math.abs
import kotlin.math.max

/**
 * PHASE 5 — TRADE JOURNAL (spec §22)
 *
 * "Every decision must be logged" — not just filled trades. A NO_TRADE, a WAIT, and a
 * RISK_BLOCKED all get an entry here, because the journal's whole purpose (spec §23: "explain
 * every trade decision to the user") is being able to answer "why didn't it trade EURUSD at
 * 14:32" just as easily as "how did that GBPUSD trade do."
 *
 * [TradeJournal] is an interface, not a Room/SQLite implementation, so a real persisted backend
 * can be swapped in later without touching [PaperTradingEngine] or anything upstream of it —
 * spec §41 asks for "modular, replaceable" APIs generally, and there's no reason the journal
 * should be an exception. [InMemoryTradeJournal] is the in-process implementation used until
 * that persistence layer exists.
 */

enum class ExecutionStatus {
    NOT_ATTEMPTED, // signal was WAIT/NO_TRADE/MARKET_UNAVAILABLE — nothing to execute
    RISK_BLOCKED,  // an actionable setup was vetoed by RiskManagementEngine
    FILLED,
    REJECTED,
    PENDING,
    UNKNOWN,       // broker response was ambiguous — spec §15/§25: never assume success or failure
    CLOSED
}

data class TradeJournalEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val pair: CurrencyPair,
    val timeframesAnalyzed: List<Timeframe>,
    val decision: TradeDecision,
    val confidenceScore: Int,
    val checks: List<ConfirmationCheck>,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val riskRewardRatio: Double?,
    val riskPercentOfEquity: Double?,
    val positionSizeLots: Double?,
    val reason: String,
    val executionStatus: ExecutionStatus,
    val brokerOrderId: String? = null,
    val filledPrice: Double? = null,
    val exitPrice: Double? = null,
    val profitLoss: Double? = null,
    val closedAtEpochMillis: Long? = null
) {
    companion object {
        fun fromSignal(
            signal: TradeSignal,
            executionStatus: ExecutionStatus,
            riskPercentOfEquity: Double? = null,
            positionSizeLots: Double? = null,
            reasonOverride: String? = null
        ) = TradeJournalEntry(
            id = signal.id,
            timestampEpochMillis = signal.generatedAtEpochMillis,
            pair = signal.pair,
            timeframesAnalyzed = signal.timeframesAnalyzed,
            decision = signal.decision,
            confidenceScore = signal.confidenceScore,
            checks = signal.checks,
            entry = signal.entry,
            stopLoss = signal.stopLoss,
            takeProfit = signal.takeProfit,
            riskRewardRatio = signal.riskRewardRatio,
            riskPercentOfEquity = riskPercentOfEquity,
            positionSizeLots = positionSizeLots,
            reason = reasonOverride ?: signal.reason,
            executionStatus = executionStatus
        )
    }
}

interface TradeJournal {
    fun record(entry: TradeJournalEntry)
    /** Applies [transform] to the existing entry with this id (e.g. attaching an exit/P&L when
     * a paper position closes) and stores the result. Returns null if no such entry exists —
     * callers should treat that as a bug (closing a trade that was never journaled as opened),
     * not a silently-ignored no-op. */
    fun update(id: String, transform: (TradeJournalEntry) -> TradeJournalEntry): TradeJournalEntry?
    fun all(): List<TradeJournalEntry>
    fun forPair(pair: CurrencyPair): List<TradeJournalEntry>
}

class InMemoryTradeJournal : TradeJournal {
    private val entries = linkedMapOf<String, TradeJournalEntry>() // insertion order preserved, keyed by id

    @Synchronized override fun record(entry: TradeJournalEntry) {
        entries[entry.id] = entry
    }

    @Synchronized override fun update(id: String, transform: (TradeJournalEntry) -> TradeJournalEntry): TradeJournalEntry? {
        val existing = entries[id] ?: return null
        val updated = transform(existing)
        entries[id] = updated
        return updated
    }

    @Synchronized override fun all(): List<TradeJournalEntry> = entries.values.toList()

    @Synchronized override fun forPair(pair: CurrencyPair): List<TradeJournalEntry> = entries.values.filter { it.pair == pair }
}

data class JournalAnalytics(
    val totalClosedTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePercent: Double,
    val averageWin: Double,
    val averageLoss: Double,
    val profitFactor: Double,
    val maxDrawdown: Double,
    val maxConsecutiveLosses: Int
)

/** Computed only from [ExecutionStatus.CLOSED] entries with a known P&L — spec §18: "do not
 * optimize solely for win rate," which is part of why this also reports profit factor and max
 * drawdown rather than win rate alone. Assumes [entries] is already in chronological order
 * (true for [InMemoryTradeJournal.all], which preserves insertion order) since drawdown and
 * consecutive-loss streaks are path-dependent, not just a function of the closed set. */
fun List<TradeJournalEntry>.toAnalytics(): JournalAnalytics {
    val closed = filter { it.executionStatus == ExecutionStatus.CLOSED && it.profitLoss != null }
    val wins = closed.filter { it.profitLoss!! > 0 }
    val losses = closed.filter { it.profitLoss!! < 0 }
    val winRate = if (closed.isEmpty()) 0.0 else wins.size.toDouble() / closed.size * 100.0
    val avgWin = if (wins.isEmpty()) 0.0 else wins.sumOf { it.profitLoss!! } / wins.size
    val avgLoss = if (losses.isEmpty()) 0.0 else losses.sumOf { it.profitLoss!! } / losses.size
    val grossProfit = wins.sumOf { it.profitLoss!! }
    val grossLoss = abs(losses.sumOf { it.profitLoss!! })
    val profitFactor = when {
        grossLoss == 0.0 && grossProfit > 0.0 -> Double.POSITIVE_INFINITY
        grossLoss == 0.0 -> 0.0
        else -> grossProfit / grossLoss
    }

    var running = 0.0
    var peak = 0.0
    var maxDrawdown = 0.0
    var consecutiveLosses = 0
    var maxConsecutiveLosses = 0
    for (e in closed) {
        running += e.profitLoss!!
        peak = max(peak, running)
        maxDrawdown = max(maxDrawdown, peak - running)
        if (e.profitLoss < 0) {
            consecutiveLosses++
            maxConsecutiveLosses = max(maxConsecutiveLosses, consecutiveLosses)
        } else {
            consecutiveLosses = 0
        }
    }

    return JournalAnalytics(closed.size, wins.size, losses.size, winRate, avgWin, avgLoss, profitFactor, maxDrawdown, maxConsecutiveLosses)
}
