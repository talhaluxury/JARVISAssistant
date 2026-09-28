package com.jarvis.assistant.core.mission

enum class MissionStatus { CREATED, QUEUED, RUNNING, PAUSED, WAITING_PERMISSION, COMPLETED, FAILED, CANCELLED }

data class Mission(
    val id: String,
    val goal: String,
    val priority: Int = 50,
    val status: MissionStatus = MissionStatus.CREATED,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastError: String? = null
)
