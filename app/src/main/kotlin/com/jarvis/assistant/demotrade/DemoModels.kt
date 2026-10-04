package com.jarvis.assistant.demotrade

import kotlinx.serialization.Serializable
import java.util.TimeZone
import kotlin.math.max

/*
 * DEMO / PAPER TRADING ONLY.
 * Nothing in this package can place a real order, tap a broker button, or touch a real-money account.
 * Everything is a local simulation that is fed by the prices JARVIS already reads from the screen.
 */

@Serializable
enum class Dir {
    CALL, PUT, WAIT;

    val sign: Int get() = when (this) { CALL -> 1; PUT -> -1; WAIT -> 0 }

    fun opposite(): Dir = when (this) { CALL -> PUT; PUT -> CALL; WAIT -> WAIT }

    companion object {
        fun fromSign(value: Double): Dir = if (value > 0.0) CALL else if (value < 0.0) PUT else WAIT
    }
}

@Serializable
enum class Regime { TREND_UP, TREND_DOWN, RANGE, HIGH_VOLATILITY, LOW_VOLATILITY, BREAKOUT, UNCERTAIN }

@Serializable
enum class RiskLevel { LOW, MEDIUM, HIGH }

@Serializable
enum class SignalStrength(val label: String) {
    NONE("NO SIGNAL"), WEAK("WEAK"), VALID("VALID"), STRONG("STRONG"), VERY_STRONG("VERY STRONG")
}

@Serializable
enum class StakeMode { FIXED, PERCENTAGE, RISK_BASED }

@Serializable
enum class TieMode { REFUND, LOSS }

@Serializable
enum class TradeResult { WIN, LOSS, TIE, VOID }

@Serializable
enum class HaltReason(val message: String) {
    NONE(""),
    DAILY_LOSS("Daily risk limit reached."),
    MAX_DRAWDOWN("Maximum drawdown reached."),
    CONSECUTIVE_LOSSES("Consecutive-loss protection activated."),
    SESSION_LOSS("Session loss limit reached.")
}

enum class EnginePhase(val label: String) {
    MARKET_SCANNING("MARKET SCANNING"),
    ANALYZING("ANALYZING"),
    CONFIRMING("CONFIRMING"),
    SIGNAL_READY("SIGNAL READY"),
    DEMO_TRADE_ACTIVE("DEMO TRADE ACTIVE"),
    TRADE_CLOSED("TRADE CLOSED"),
    RISK_LIMIT("RISK LIMIT"),
    WAITING_FOR_DATA("WAITING FOR DATA")
}

val DEFAULT_STRATEGY_IDS: Set<String> =
    linkedSetOf("trend", "ema_rsi", "macd", "breakout", "sr_rejection", "pullback", "multi", "squeeze", "vwap", "stoch_reversal")

/** Start of the local calendar day, expressed as a day number (used for the daily risk limits). */
fun dayKeyOf(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

fun hourOf(ms: Long): Int = (((ms + TimeZone.getDefault().getOffset(ms)) / 3_600_000L) % 24L).toInt().let { if (it < 0) it + 24 else it }

@Serializable
data class DemoSettings(
    val autoDemoTrading: Boolean = false,
    val initialBalance: Double = 10_000.0,
    val minConfidence: Int = 70,
    val weakThreshold: Int = 55,
    val validThreshold: Int = 70,
    val strongThreshold: Int = 80,
    val veryStrongThreshold: Int = 90,
    val stakeMode: StakeMode = StakeMode.FIXED,
    val fixedStake: Double = 10.0,
    val stakePercent: Double = 0.5,
    val riskPercent: Double = 1.0,
    val maxStake: Double = 100.0,
    val maxRiskPerTradePercent: Double = 2.0,
    val payoutPercent: Double = 80.0,
    val tieMode: TieMode = TieMode.REFUND,
    val expirySeconds: Int = 60,
    val maxTradesPerHour: Int = 6,
    val maxDailyTrades: Int = 30,
    val maxConsecutiveLosses: Int = 4,
    val cooldownSeconds: Int = 120,
    val cooldownAfterLossSeconds: Int = 300,
    val postLossExtraConfidence: Int = 5,
    val maxConcurrentTrades: Int = 1,
    val maxDailyLossPercent: Double = 5.0,
    val dailyTargetPercent: Double = 5.0,
    val maxDrawdownPercent: Double = 20.0,
    val sessionLossPercent: Double = 10.0,
    val signalValiditySeconds: Int = 20,
    val settlementGraceSeconds: Int = 15,
    val minConfirmations: Int = 2,
    val enabledStrategies: Set<String> = DEFAULT_STRATEGY_IDS,
    val useAi: Boolean = true,
    val useMultiTimeframe: Boolean = true,
    val adaptiveEnabled: Boolean = false,
    val adaptiveMaxBump: Int = 10,
    val adaptiveMinTrades: Int = 30,
    val minCandles: Int = 60,
    val soundOn: Boolean = true,
    val notificationsOn: Boolean = true
) {
    val payoutRatio: Double get() = payoutPercent / 100.0

    /** Win rate needed to break even: lose the whole stake on a miss, win [payoutRatio] x stake on a hit. */
    val breakEvenWinRate: Double get() = 1.0 / (1.0 + payoutRatio)

    fun strengthOf(confidence: Int): SignalStrength = when {
        confidence < weakThreshold -> SignalStrength.NONE
        confidence < validThreshold -> SignalStrength.WEAK
        confidence < strongThreshold -> SignalStrength.VALID
        confidence < veryStrongThreshold -> SignalStrength.STRONG
        else -> SignalStrength.VERY_STRONG
    }

    /** Clamp every value into a sane range so a typo in Settings can never disable a safety limit by accident. */
    fun coerced(): DemoSettings {
        val weak = weakThreshold.coerceIn(30, 95)
        val valid = validThreshold.coerceIn(weak, 98)
        val strong = strongThreshold.coerceIn(valid, 99)
        val very = veryStrongThreshold.coerceIn(strong, 100)
        val strategies = enabledStrategies.filter { it in DEFAULT_STRATEGY_IDS }.toSet()
        return copy(
            initialBalance = initialBalance.coerceIn(100.0, 10_000_000.0),
            weakThreshold = weak,
            validThreshold = valid,
            strongThreshold = strong,
            veryStrongThreshold = very,
            minConfidence = minConfidence.coerceIn(weak, 100),
            fixedStake = fixedStake.coerceIn(1.0, 100_000.0),
            stakePercent = stakePercent.coerceIn(0.1, 10.0),
            riskPercent = riskPercent.coerceIn(0.1, 5.0),
            maxStake = maxStake.coerceIn(1.0, 100_000.0),
            maxRiskPerTradePercent = maxRiskPerTradePercent.coerceIn(0.1, 10.0),
            payoutPercent = payoutPercent.coerceIn(30.0, 100.0),
            expirySeconds = expirySeconds.coerceIn(5, 3600),
            maxTradesPerHour = maxTradesPerHour.coerceIn(1, 60),
            maxDailyTrades = maxDailyTrades.coerceIn(1, 500),
            maxConsecutiveLosses = maxConsecutiveLosses.coerceIn(1, 20),
            cooldownSeconds = cooldownSeconds.coerceIn(0, 3600),
            cooldownAfterLossSeconds = cooldownAfterLossSeconds.coerceIn(0, 7200),
            postLossExtraConfidence = postLossExtraConfidence.coerceIn(0, 20),
            maxConcurrentTrades = maxConcurrentTrades.coerceIn(1, 3),
            maxDailyLossPercent = maxDailyLossPercent.coerceIn(0.5, 50.0),
            dailyTargetPercent = dailyTargetPercent.coerceIn(0.0, 100.0),
            maxDrawdownPercent = maxDrawdownPercent.coerceIn(1.0, 90.0),
            sessionLossPercent = sessionLossPercent.coerceIn(1.0, 90.0),
            signalValiditySeconds = signalValiditySeconds.coerceIn(5, 120),
            settlementGraceSeconds = settlementGraceSeconds.coerceIn(2, 60),
            minConfirmations = minConfirmations.coerceIn(1, 7),
            enabledStrategies = if (strategies.isEmpty()) DEFAULT_STRATEGY_IDS else strategies,
            adaptiveMaxBump = adaptiveMaxBump.coerceIn(0, 20),
            adaptiveMinTrades = adaptiveMinTrades.coerceIn(10, 500),
            minCandles = minCandles.coerceIn(55, 500)
        )
    }
}

@Serializable
data class StrategyVote(
    val strategy: String,
    val direction: Dir,
    val score: Int,
    val reasons: List<String>
)

/** One weighted category of the transparent scoring engine. [alignment] is -1..1 *toward the signal direction*. */
@Serializable
data class ScoreComponent(val name: String, val weight: Double, val alignment: Double, val note: String = "") {
    val points: Double get() = weight * alignment
}

/**
 * The final decision for one closed candle. [confidence] is the MODEL's 0..100 score - it is NOT a probability of winning.
 */
@Serializable
data class TradeSignal(
    val direction: Dir,
    val confidence: Int,
    val entryPrice: Double,
    val timestampMs: Long,
    val candleTimeMs: Long,
    val expirySeconds: Int,
    val validUntilMs: Long,
    val regime: Regime,
    val confirmations: List<String>,
    val reasons: List<String>,
    val riskLevel: RiskLevel,
    val stake: Double,
    val strength: SignalStrength,
    val asset: String = "",
    val votes: List<StrategyVote> = emptyList(),
    val components: List<ScoreComponent> = emptyList(),
    val technicalScore: Int = 0,
    val ensembleScore: Int = 0,
    val aiDirection: Dir? = null,
    val aiConfidence: Int? = null,
    val indicators: Map<String, Double> = emptyMap(),
    val atr: Double = 0.0,
    val payoutRatio: Double = 0.8,
    val breakEvenWinRate: Double = 0.5555,
    val mtfNote: String = "",
    val blockReason: String? = null
) {
    val isActionable: Boolean get() = direction != Dir.WAIT
    val modelConfidenceLabel: String get() = "Model confidence: $confidence/100"
}

@Serializable
data class PaperTrade(
    val id: Int,
    val asset: String,
    val direction: Dir,
    val openedAtMs: Long,
    val expiresAtMs: Long,
    val expirySeconds: Int,
    val entryPrice: Double,
    val stake: Double,
    val payoutPercent: Double,
    val confidence: Int,
    val strength: SignalStrength,
    val regime: Regime,
    val strategies: List<String>,
    val reasons: List<String>,
    val confirmations: List<String>,
    val indicators: Map<String, Double>,
    val atrAtEntry: Double,
    val riskLevel: RiskLevel,
    val aiDirection: Dir? = null,
    val mtfNote: String = "",
    val tieLoses: Boolean = false,
    val exitPrice: Double? = null,
    val closedAtMs: Long? = null,
    val result: TradeResult? = null,
    val pnl: Double? = null,
    val balanceAfter: Double? = null,
    val maxFavorable: Double = 0.0,
    val maxAdverse: Double = 0.0,
    val explanation: String = ""
) {
    val isOpen: Boolean get() = result == null
}

@Serializable
data class SignalRecord(
    val timeMs: Long,
    val direction: Dir,
    val confidence: Int,
    val regime: Regime,
    val traded: Boolean,
    val note: String
)

@Serializable
data class DemoAccount(
    val balance: Double,
    val initialBalance: Double,
    val openStake: Double = 0.0,
    val peakEquity: Double,
    val dayKey: Long = 0L,
    val dayStartBalance: Double,
    val dayPnl: Double = 0.0,
    val dayTrades: Int = 0,
    val dayWins: Int = 0,
    val dayLosses: Int = 0,
    val recentOpenTimes: List<Long> = emptyList(),
    val consecutiveLosses: Int = 0,
    val lastOpenMs: Long = 0L,
    val lastLossMs: Long = 0L,
    val sessionStartBalance: Double,
    val halt: HaltReason = HaltReason.NONE,
    val dailyTargetNotified: Boolean = false,
    val totalTrades: Int = 0
) {
    val equity: Double get() = balance + openStake
    val drawdownPct: Double get() = if (peakEquity > 0.0) max(0.0, (peakEquity - equity) / peakEquity * 100.0) else 0.0

    /** Roll the per-day counters when the local calendar day changes. Daily and consecutive-loss halts end with the day. */
    fun rolled(nowMs: Long): DemoAccount {
        val key = dayKeyOf(nowMs)
        if (key == dayKey) return this
        val clearHalt = halt == HaltReason.DAILY_LOSS || halt == HaltReason.CONSECUTIVE_LOSSES
        return copy(
            dayKey = key,
            dayStartBalance = equity,
            dayPnl = 0.0,
            dayTrades = 0,
            dayWins = 0,
            dayLosses = 0,
            dailyTargetNotified = false,
            halt = if (clearHalt) HaltReason.NONE else halt,
            consecutiveLosses = if (clearHalt) 0 else consecutiveLosses
        )
    }

    companion object {
        fun fresh(initial: Double, nowMs: Long): DemoAccount = DemoAccount(
            balance = initial,
            initialBalance = initial,
            peakEquity = initial,
            dayKey = dayKeyOf(nowMs),
            dayStartBalance = initial,
            sessionStartBalance = initial
        )
    }
}
