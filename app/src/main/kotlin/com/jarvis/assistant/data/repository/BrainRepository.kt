package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.local.db.dao.CommandHistoryDao
import com.jarvis.assistant.data.local.db.dao.CommandUsageCount
import com.jarvis.assistant.data.local.db.dao.PreferenceDao
import com.jarvis.assistant.data.local.db.dao.SystemEventDao
import com.jarvis.assistant.data.local.db.dao.TaskOutcomeDao
import com.jarvis.assistant.data.local.db.entity.CommandHistoryEntity
import com.jarvis.assistant.data.local.db.entity.PreferenceEntity
import com.jarvis.assistant.data.local.db.entity.SystemEventEntity
import com.jarvis.assistant.data.local.db.entity.TaskOutcomeEntity
import kotlinx.coroutines.flow.Flow

class BrainRepository(
    private val commandHistoryDao: CommandHistoryDao,
    private val taskOutcomeDao: TaskOutcomeDao,
    private val systemEventDao: SystemEventDao,
    private val preferenceDao: PreferenceDao
) {
    // --- Command history (#38 layer 5) --------------------------------------------------
    suspend fun logCommand(commandType: String, description: String, success: Boolean, resultMessage: String) {
        commandHistoryDao.insert(CommandHistoryEntity(commandType = commandType, description = description, success = success, resultMessage = resultMessage))
    }

    suspend fun recentCommands(limit: Int = 20): List<CommandHistoryEntity> = commandHistoryDao.recent(limit)
    suspend fun lastCommand(): CommandHistoryEntity? = commandHistoryDao.last()
    suspend fun commandUsageCounts(limit: Int = 10): List<CommandUsageCount> = commandHistoryDao.usageCounts(limit)
    suspend fun clearCommandHistory() = commandHistoryDao.clearAll()

    // --- Task outcomes (#44 learning engine) --------------------------------------------
    suspend fun logTaskOutcome(
        userRequest: String, intentCategory: String, stepCount: Int,
        success: Boolean, failureReason: String?, executionTimeMs: Long
    ) {
        taskOutcomeDao.insert(
            TaskOutcomeEntity(
                userRequest = userRequest, intentCategory = intentCategory, stepCount = stepCount,
                success = success, failureReason = failureReason, executionTimeMs = executionTimeMs
            )
        )
    }

    suspend fun recentOutcomes(limit: Int = 200): List<TaskOutcomeEntity> = taskOutcomeDao.recent(limit)
    fun observeOutcomes(): Flow<List<TaskOutcomeEntity>> = taskOutcomeDao.observeAll()
    suspend fun totalTasks(): Int = taskOutcomeDao.totalCount()
    suspend fun successfulTasks(): Int = taskOutcomeDao.successCount()
    suspend fun clearTaskOutcomes() = taskOutcomeDao.clearAll()

    // --- System events (#48 diagnostic history) -----------------------------------------
    suspend fun logEvent(category: String, message: String) {
        systemEventDao.insert(SystemEventEntity(category = category, message = message))
    }

    suspend fun recentEvents(limit: Int = 50): List<SystemEventEntity> = systemEventDao.recent(limit)
    suspend fun clearEvents() = systemEventDao.clearAll()

    // --- Structured preferences (#38 layer 7) -------------------------------------------
    suspend fun setPreference(key: String, value: String) = preferenceDao.upsert(PreferenceEntity(key = key, value = value))
    suspend fun getPreference(key: String): String? = preferenceDao.get(key)?.value
    suspend fun allPreferences(): List<PreferenceEntity> = preferenceDao.getAll()
    suspend fun clearPreference(key: String) = preferenceDao.delete(key)
    suspend fun clearAllPreferences() = preferenceDao.clearAll()
}
