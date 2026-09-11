package com.jarvis.assistant.core.state

import com.jarvis.assistant.agent.CapabilityStatusEntry
import com.jarvis.assistant.agent.TaskStatus

/** Single runtime state model used as the source of truth for HUD/console/agent orchestration. */
data class JarvisState(
    val mode: JarvisMode = JarvisMode.STANDBY,
    val taskId: String? = null,
    val taskStatus: TaskStatus = TaskStatus.IDLE,
    val currentApp: String? = null,
    val capabilities: List<CapabilityStatusEntry> = emptyList(),
    val confidence: ConfidenceState = ConfidenceState(),
    val risk: RiskLevel = RiskLevel.LOW,
    val simulation: Boolean = false,
    val emergencyStop: Boolean = false,
    val focusMode: Boolean = false,
    val silentMode: Boolean = false,
    val safeMode: Boolean = false,
    val activeMissionCount: Int = 0,
    val failedMissionCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class JarvisMode {
    BOOTING, STANDBY, LISTENING, THINKING, UNDERSTANDING, CONTEXTUALIZING, PLANNING,
    CONFIRMING, EXECUTING, VERIFYING, RECOVERING, SPEAKING, PAUSED, DIAGNOSTIC,
    ERROR, EMERGENCY_STOP, SAFE_MODE
}

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

data class ConfidenceState(
    val intent: Float = 1f,
    val action: Float = 1f,
    val verification: Float = 1f,
    val overall: Float = 1f
) {
    fun normalized(): ConfidenceState = copy(
        intent = intent.coerceIn(0f, 1f), action = action.coerceIn(0f, 1f),
        verification = verification.coerceIn(0f, 1f), overall = overall.coerceIn(0f, 1f)
    )
}
