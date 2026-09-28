package com.jarvis.assistant.trading

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PHASE 1 — EMERGENCY STOP / DAILY RISK LOCK (spec §18, §20, §38)
 *
 * Only the user can clear an emergency stop or a daily-loss lock — there is deliberately no
 * method here that lets the signal/AI layer call `reset()`. `recordLoss` / `recordDailyLoss`
 * are the only ways a lock gets set automatically, and both are meant to be called exclusively
 * by the (later-phase) trade-outcome pipeline, never by analysis code.
 *
 * Not persisted yet (spec's own phase ordering puts durable storage with the trade journal /
 * settings persistence, not with this controller) — an app restart currently resets to
 * "trading allowed", which is safe-by-default rather than silently unsafe, but should be
 * revisited once TradingSettings persistence exists.
 */
class EmergencyStopController(
    private val riskSettings: () -> RiskSettings
) {
    private val _state = MutableStateFlow(TradingLockState())
    val state: StateFlow<TradingLockState> = _state.asStateFlow()

    fun isTradingAllowed(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        _state.value.isTradingAllowed(nowEpochMillis)

    /** Spec §20: "Only the user can reactivate trading." Called from an explicit UI/voice
     * action, never automatically. */
    fun activateEmergencyStop(reason: String) {
        _state.value = _state.value.copy(emergencyStopActive = true, lockReason = reason)
    }

    fun userClearEmergencyStop() {
        _state.value = _state.value.copy(emergencyStopActive = false, lockReason = null)
    }

    /** Spec §38: daily loss lock is separate from emergency stop and is cleared by the next
     * configured trading period starting, not by the user directly overriding it mid-session —
     * callers should route that through [rolloverNewTradingDay], not this. */
    fun triggerDailyLossLock(reason: String) {
        _state.value = _state.value.copy(dailyLossLockActive = true, lockReason = reason)
    }

    fun rolloverNewTradingDay() {
        _state.value = _state.value.copy(dailyLossLockActive = false, consecutiveLosses = 0, lockReason = null)
    }

    /** Spec §8/§13: cooldown after a losing streak, never a manual risk override. */
    fun recordLoss(nowEpochMillis: Long = System.currentTimeMillis()) {
        val settings = riskSettings()
        val streak = _state.value.consecutiveLosses + 1
        val cooldownUntil = if (streak >= settings.maxConsecutiveLosses) {
            nowEpochMillis + settings.cooldownAfterLossMinutes * 60_000L
        } else {
            _state.value.cooldownUntilEpochMillis
        }
        _state.value = _state.value.copy(
            consecutiveLosses = streak,
            cooldownUntilEpochMillis = cooldownUntil,
            lockReason = if (cooldownUntil != null) "Cooldown after $streak consecutive losses." else _state.value.lockReason
        )
    }

    fun recordWin() {
        _state.value = _state.value.copy(consecutiveLosses = 0, cooldownUntilEpochMillis = null)
    }
}
