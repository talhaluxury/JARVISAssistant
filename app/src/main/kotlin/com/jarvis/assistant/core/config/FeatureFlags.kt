package com.jarvis.assistant.core.config

/** Runtime feature flags. Defaults are conservative; risky features stay opt-in. */
data class FeatureFlags(
    val developerSimulation: Boolean = false,
    val proactiveSuggestions: Boolean = true,
    val workflowEngine: Boolean = true,
    val parallelTasks: Boolean = false,
    val cloudFallback: Boolean = true,
    val safeMode: Boolean = false
)
