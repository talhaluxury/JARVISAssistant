package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.analysis.VolatilityState
import com.jarvis.assistant.trading.QuotexDecision
import kotlin.math.abs

enum class LifecycleState { NONE, ACTIVE, EXPIRED, INVALIDATED }

data class LifecycleReading(val state: LifecycleState, val reason: String?, val ageSeconds: Long, val remainingSeconds: Long)

/**
 * Section 16: a setup never stays on screen as if it were still valid. It ends when conditions change,
 * price runs too far from the entry zone, volatility jumps, an opposing signal appears or its window closes.
 */
class SignalLifecycle(private val validityMs: Long, private val maxMoveAtr: Double = 1.0) {

    private class Active(
        val direction: QuotexDecision, val createdMs: Long, val entryPrice: Double,
        val entryAtr: Double?, val volatility: VolatilityState
    )

    private var active: Active? = null

    fun update(report: AgentReport, nowMs: Long): LifecycleReading {
        val a = active
        if (a == null) {
            if (report.status == AgentStatus.SETUP_DETECTED && report.direction != QuotexDecision.WAIT && report.lastPrice != null) {
                active = Active(report.direction, nowMs, report.lastPrice, report.atr, report.volatility)
                return LifecycleReading(LifecycleState.ACTIVE, null, 0L, validityMs / 1000L)
            }
            return LifecycleReading(LifecycleState.NONE, null, 0L, 0L)
        }

        val ageMs = maxOf(0L, nowMs - a.createdMs)
        val ageS = ageMs / 1000L
        fun end(state: LifecycleState, why: String): LifecycleReading {
            active = null
            return LifecycleReading(state, why, ageS, 0L)
        }

        val price = report.lastPrice
        return when {
            ageMs >= validityMs -> end(LifecycleState.EXPIRED, "Setup validity window ended.")
            report.status == AgentStatus.NO_TRADE || report.status == AgentStatus.DATA_UNCERTAIN ->
                end(LifecycleState.INVALIDATED, "Conditions changed: ${report.headlineReason}")
            report.direction != QuotexDecision.WAIT && report.direction != a.direction ->
                end(LifecycleState.INVALIDATED, "An opposing signal appeared.")
            report.status == AgentStatus.WAIT -> end(LifecycleState.INVALIDATED, "Conditions no longer support the setup.")
            (report.volatility == VolatilityState.HIGH || report.volatility == VolatilityState.EXTREME) &&
                a.volatility != VolatilityState.HIGH && a.volatility != VolatilityState.EXTREME ->
                end(LifecycleState.INVALIDATED, "Volatility rose sharply.")
            price != null && a.entryAtr != null && a.entryAtr > 0.0 && abs(price - a.entryPrice) > a.entryAtr * maxMoveAtr ->
                end(LifecycleState.INVALIDATED, "Price moved too far from the entry zone.")
            else -> LifecycleReading(LifecycleState.ACTIVE, null, ageS, maxOf(0L, (validityMs - ageMs) / 1000L))
        }
    }

    fun reset() { active = null }
}
