package com.jarvis.assistant.demotrade

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

data class RiskDecision(val approved: Boolean, val stake: Double, val reason: String)

/** Bounded, tighten-only suggestions produced by [AdaptiveFilter]. */
data class AdaptiveAdvice(
    val minConfidenceBump: Int = 0,
    val blockedRegimes: Set<Regime> = emptySet(),
    val blockedHours: Set<Int> = emptySet(),
    val notes: List<String> = emptyList()
) {
    companion object {
        val NONE = AdaptiveAdvice()
    }
}

object RiskManager {
    private const val POST_LOSS_WINDOW_MS = 30L * 60_000L
    const val MIN_STAKE = 1.0

    /** Hard stops. Once one of these fires, auto trading stays stopped (see [DemoAccount.rolled] for when it clears). */
    fun checkHalts(acct: DemoAccount, s: DemoSettings): HaltReason {
        if (acct.halt != HaltReason.NONE) return acct.halt
        val dayLimit = acct.dayStartBalance * s.maxDailyLossPercent / 100.0
        if (-acct.dayPnl >= dayLimit - 1e-9) return HaltReason.DAILY_LOSS
        if (acct.drawdownPct >= s.maxDrawdownPercent) return HaltReason.MAX_DRAWDOWN
        val sessionLimit = acct.sessionStartBalance * s.sessionLossPercent / 100.0
        if (acct.sessionStartBalance - acct.equity >= sessionLimit - 1e-9) return HaltReason.SESSION_LOSS
        if (acct.consecutiveLosses >= s.maxConsecutiveLosses) return HaltReason.CONSECUTIVE_LOSSES
        return HaltReason.NONE
    }

    fun stakeFor(signal: TradeSignal, acct: DemoAccount, s: DemoSettings): Double {
        val balance = acct.balance
        var stake = when (s.stakeMode) {
            StakeMode.FIXED -> s.fixedStake
            StakeMode.PERCENTAGE -> balance * s.stakePercent / 100.0
            StakeMode.RISK_BASED -> balance * s.riskPercent / 100.0 * when (signal.riskLevel) {
                RiskLevel.LOW -> 1.0
                RiskLevel.MEDIUM -> 0.75
                RiskLevel.HIGH -> 0.5
            }
        }
        stake = min(stake, s.maxStake)
        stake = min(stake, balance * s.maxRiskPerTradePercent / 100.0)
        stake = min(stake, balance)
        // Deliberately NO progressive / martingale staking anywhere: the stake never grows because of a loss.
        return floor(stake * 100.0) / 100.0
    }

    fun requiredConfidence(acct: DemoAccount, s: DemoSettings, nowMs: Long, adaptive: AdaptiveAdvice): Int {
        var required = s.minConfidence + adaptive.minConfidenceBump
        if (acct.consecutiveLosses > 0 && acct.lastLossMs > 0L && nowMs - acct.lastLossMs < POST_LOSS_WINDOW_MS) {
            required += s.postLossExtraConfidence
        }
        return min(100, required)
    }

    fun evaluate(
        signal: TradeSignal,
        acct: DemoAccount,
        s: DemoSettings,
        nowMs: Long,
        activeCount: Int,
        adaptive: AdaptiveAdvice
    ): RiskDecision {
        fun no(msg: String) = RiskDecision(false, 0.0, "Risk: $msg")
        if (signal.direction == Dir.WAIT) return no("signal is WAIT")
        if (nowMs > signal.validUntilMs) return no("signal expired")
        val halt = checkHalts(acct, s)
        if (halt != HaltReason.NONE) return no(halt.message)
        if (acct.dayTrades >= s.maxDailyTrades) return no("daily trade limit reached (${s.maxDailyTrades})")
        val lastHour = acct.recentOpenTimes.count { nowMs - it < 3_600_000L }
        if (lastHour >= s.maxTradesPerHour) return no("hourly trade limit reached (${s.maxTradesPerHour})")
        if (activeCount >= s.maxConcurrentTrades) return no("a demo trade is already running")
        if (acct.lastOpenMs > 0L && nowMs - acct.lastOpenMs < s.cooldownSeconds * 1000L) return no("cooldown after trade is active")
        if (acct.consecutiveLosses > 0 && acct.lastLossMs > 0L && nowMs - acct.lastLossMs < s.cooldownAfterLossSeconds * 1000L) {
            return no("cooldown after loss is active")
        }
        if (signal.regime in adaptive.blockedRegimes) return no("adaptive filter blocks regime ${signal.regime}")
        if (hourOf(nowMs) in adaptive.blockedHours) return no("adaptive filter blocks this hour")
        val required = requiredConfidence(acct, s, nowMs, adaptive)
        if (signal.confidence < required) return no("confidence ${signal.confidence} is below the required $required")
        val stake = stakeFor(signal, acct, s)
        if (stake < MIN_STAKE) return no("stake below the minimum (insufficient demo balance or limits)")
        return RiskDecision(true, stake, "approved")
    }
}

object PaperTradeExecutor {

    fun decide(direction: Dir, entry: Double, exit: Double): TradeResult = when {
        exit == entry -> TradeResult.TIE
        direction == Dir.CALL -> if (exit > entry) TradeResult.WIN else TradeResult.LOSS
        direction == Dir.PUT -> if (exit < entry) TradeResult.WIN else TradeResult.LOSS
        else -> TradeResult.VOID
    }

    fun open(acct: DemoAccount, id: Int, signal: TradeSignal, stake: Double, s: DemoSettings, nowMs: Long): Pair<DemoAccount, PaperTrade> {
        val trade = PaperTrade(
            id = id,
            asset = signal.asset,
            direction = signal.direction,
            openedAtMs = nowMs,
            expiresAtMs = nowMs + signal.expirySeconds * 1000L,
            expirySeconds = signal.expirySeconds,
            entryPrice = signal.entryPrice,
            stake = stake,
            payoutPercent = s.payoutPercent,
            confidence = signal.confidence,
            strength = signal.strength,
            regime = signal.regime,
            strategies = signal.votes.filter { it.direction == signal.direction }.map { it.strategy },
            reasons = signal.reasons,
            confirmations = signal.confirmations,
            indicators = signal.indicators,
            atrAtEntry = signal.atr,
            riskLevel = signal.riskLevel,
            aiDirection = signal.aiDirection,
            mtfNote = signal.mtfNote,
            tieLoses = s.tieMode == TieMode.LOSS
        )
        val recent = acct.recentOpenTimes.filter { nowMs - it < 3_600_000L } + nowMs
        val next = acct.copy(
            balance = round2(acct.balance - stake),
            openStake = round2(acct.openStake + stake),
            recentOpenTimes = recent,
            lastOpenMs = nowMs,
            dayTrades = acct.dayTrades + 1,
            totalTrades = acct.totalTrades + 1
        )
        return Pair(next, trade)
    }

    /** Close [trade] with [result]. Accounting: the stake was deducted at open; WIN pays stake + profit, TIE refunds (or not), VOID refunds. */
    fun settle(acct: DemoAccount, trade: PaperTrade, exitPrice: Double?, result: TradeResult, nowMs: Long): Pair<DemoAccount, PaperTrade> {
        val profit = round2(trade.stake * trade.payoutPercent / 100.0)
        val outcome: Pair<Double, Double> = when (result) {
            TradeResult.WIN -> Pair(trade.stake + profit, profit)
            TradeResult.LOSS -> Pair(0.0, -trade.stake)
            TradeResult.TIE -> if (trade.tieLoses) Pair(0.0, -trade.stake) else Pair(trade.stake, 0.0)
            TradeResult.VOID -> Pair(trade.stake, 0.0)
        }
        val credit = outcome.first
        val pnl = outcome.second
        val balance = round2(acct.balance + credit)
        val openStake = max(0.0, round2(acct.openStake - trade.stake))
        val lost = pnl < 0.0
        val next = acct.copy(
            balance = balance,
            openStake = openStake,
            consecutiveLosses = when {
                result == TradeResult.WIN -> 0
                result == TradeResult.LOSS -> acct.consecutiveLosses + 1
                lost -> acct.consecutiveLosses + 1
                else -> acct.consecutiveLosses
            },
            lastLossMs = if (lost) nowMs else acct.lastLossMs,
            dayPnl = round2(acct.dayPnl + pnl),
            dayWins = acct.dayWins + if (result == TradeResult.WIN) 1 else 0,
            dayLosses = acct.dayLosses + if (lost) 1 else 0,
            peakEquity = max(acct.peakEquity, balance + openStake)
        )
        val closed = trade.copy(
            exitPrice = exitPrice,
            closedAtMs = nowMs,
            result = result,
            pnl = round2(pnl),
            balanceAfter = balance
        )
        return Pair(next, closed)
    }
}
