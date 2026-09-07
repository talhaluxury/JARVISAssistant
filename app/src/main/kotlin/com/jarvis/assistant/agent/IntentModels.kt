package com.jarvis.assistant.agent

/** #39 INTENT ENGINE — the closed set of structured intent categories JARVIS reasons about. */
enum class IntentCategory {
    OPEN_APP, CLOSE_APP, NAVIGATE, SEARCH, TYPE, TAP, SCROLL, READ,
    SEND_MESSAGE, CALL,
    DEVICE_STATUS, BATTERY_STATUS, NETWORK_STATUS,
    MEMORY_QUERY, MEMORY_WRITE, MEMORY_FORGET,
    SYSTEM_DIAGNOSTIC,
    MULTI_STEP_TASK,
    INFORMATION_REQUEST,
    CONVERSATION,
    FOLLOW_UP, CLARIFICATION, CONFIRMATION, CANCELLATION, RETRY, PAUSE, RESUME,
    UNKNOWN
}

enum class IntentConfidence { HIGH, MEDIUM, LOW }

/**
 * A structured, machine-checkable interpretation of one utterance. [slots] holds whatever
 * free-form arguments were extracted (e.g. "target" -> "YouTube", "query" -> "Ali's channel
 * latest video"). This is deliberately a plain map rather than a hierarchy of DTOs so new
 * slot kinds never require a schema migration — CommandEngine/AgentPlanner still do the real,
 * type-safe validation before anything executes.
 */
data class ParsedIntent(
    val category: IntentCategory,
    val slots: Map<String, String> = emptyMap(),
    val confidence: IntentConfidence,
    val rawText: String,
    val resolvedFromContext: Boolean = false
)
