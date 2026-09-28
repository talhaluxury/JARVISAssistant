package com.jarvis.assistant.core.state

/** Read-only digital twin projection. It never writes to Android state. */
data class DigitalTwin(
    val currentApp: String?,
    val networkOnline: Boolean,
    val batteryPercent: Int,
    val charging: Boolean,
    val accessibilityEnabled: Boolean,
    val taskStatus: String,
    val updatedAt: Long
)
