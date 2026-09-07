package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand

/** #52 TASK STATE MACHINE — the full explicit state set. CREATED/UNDERSTANDING/PLANNING happen
 * before TaskEngine.run() is even called (intent classification + AgentPlanner); TaskEngine
 * itself drives EXECUTING -> VERIFYING -> (RECOVERING on failure) -> COMPLETED/FAILED, and
 * WAITING_PERMISSION/CANCELLED can interrupt from the ViewModel at any point. */
enum class TaskStatus {
    IDLE, CREATED, UNDERSTANDING, PLANNING, WAITING_PERMISSION,
    EXECUTING, VERIFYING, RECOVERING, PAUSED, COMPLETED, FAILED, CANCELLED
}

/** #43 ERROR RECOVERY ENGINE — a coarse, honest classification of why a step failed, used for
 * both the spoken failure message and the learning engine's pattern detection. Never guesses
 * beyond what the executor/accessibility layer actually reported. */
enum class FailureReason {
    ELEMENT_NOT_FOUND, APP_NOT_RESPONDING, PERMISSION_MISSING, NETWORK_UNAVAILABLE, UNKNOWN;

    companion object {
        fun classify(message: String): FailureReason {
            val lower = message.lowercase()
            return when {
                lower.contains("permission") || lower.contains("accessibility") || lower.contains("phone control") -> PERMISSION_MISSING
                lower.contains("network") || lower.contains("offline") || lower.contains("internet") -> NETWORK_UNAVAILABLE
                lower.contains("not found") || lower.contains("no app found") || lower.contains("isn't installed") -> ELEMENT_NOT_FOUND
                lower.contains("busy") || lower.contains("not responding") || lower.contains("couldn't complete") -> APP_NOT_RESPONDING
                else -> UNKNOWN
            }
        }
    }
}

data class AgentTask(
    val id: String,
    val userRequest: String,
    val steps: List<JarvisCommand>,
    val currentStep: Int = 0,
    val status: TaskStatus = TaskStatus.IDLE,
    val completedSteps: List<String> = emptyList(),
    val failedSteps: List<String> = emptyList(),
    val retryCount: Int = 0,
    val failureReason: FailureReason? = null,
    val startedAt: Long = System.currentTimeMillis()
)
