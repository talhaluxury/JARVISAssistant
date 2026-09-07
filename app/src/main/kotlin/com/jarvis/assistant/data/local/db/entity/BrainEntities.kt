package com.jarvis.assistant.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * #38 ADVANCED MEMORY ARCHITECTURE — the additional layers beyond flat user-approved memory
 * (that one stays as [MemoryEntity], now carrying its own metadata — see the migration in
 * JarvisDatabase). Each layer below has one job:
 *
 *  - [CommandHistoryEntity]: every command that actually ran, for "what did you just do" /
 *    RETRY, and as the AGENT_PLAN failure-pattern feed for the learning engine.
 *  - [TaskOutcomeEntity]: one row per completed/failed plan or command, for #44 LEARNING.
 *  - [SystemEventEntity]: non-command lifecycle events (permission changes, connectivity
 *    changes, service state) used by the self-diagnostic engine's health history.
 *  - [PreferenceEntity]: small user-approved key/value preferences distinct from free-text
 *    memory (e.g. "wake_word_language" -> "ur"), queryable without scanning memory text.
 */
@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandType: String,
    val description: String,
    val success: Boolean,
    val resultMessage: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_outcomes")
data class TaskOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userRequest: String,
    val intentCategory: String,
    val stepCount: Int,
    val success: Boolean,
    val failureReason: String?,
    val executionTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "system_events")
data class SystemEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // AI, MEMORY, VOICE, ACCESSIBILITY, NETWORK, PERMISSIONS, TASK_ENGINE, ...
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "preferences_memory")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
