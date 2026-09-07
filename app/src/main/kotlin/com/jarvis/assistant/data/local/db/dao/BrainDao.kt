package com.jarvis.assistant.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.data.local.db.entity.CommandHistoryEntity
import com.jarvis.assistant.data.local.db.entity.PreferenceEntity
import com.jarvis.assistant.data.local.db.entity.SystemEventEntity
import com.jarvis.assistant.data.local.db.entity.TaskOutcomeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandHistoryDao {
    @Insert
    suspend fun insert(entry: CommandHistoryEntity): Long

    @Query("SELECT * FROM command_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<CommandHistoryEntity>

    @Query("SELECT * FROM command_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun last(): CommandHistoryEntity?

    @Query("SELECT commandType, COUNT(*) as count, SUM(CASE WHEN success THEN 1 ELSE 0 END) as successCount FROM command_history GROUP BY commandType ORDER BY count DESC LIMIT :limit")
    suspend fun usageCounts(limit: Int = 10): List<CommandUsageCount>

    @Query("DELETE FROM command_history")
    suspend fun clearAll()
}

/** Simple Room query POJO — not an @Entity, just a shape for the grouped usage-count query. */
data class CommandUsageCount(val commandType: String, val count: Int, val successCount: Int)

@Dao
interface TaskOutcomeDao {
    @Insert
    suspend fun insert(entry: TaskOutcomeEntity): Long

    @Query("SELECT * FROM task_outcomes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<TaskOutcomeEntity>

    @Query("SELECT * FROM task_outcomes ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TaskOutcomeEntity>>

    @Query("SELECT COUNT(*) FROM task_outcomes")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM task_outcomes WHERE success = 1")
    suspend fun successCount(): Int

    @Query("DELETE FROM task_outcomes")
    suspend fun clearAll()
}

@Dao
interface SystemEventDao {
    @Insert
    suspend fun insert(entry: SystemEventEntity): Long

    @Query("SELECT * FROM system_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<SystemEventEntity>

    @Query("DELETE FROM system_events")
    suspend fun clearAll()
}

@Dao
interface PreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: PreferenceEntity)

    @Query("SELECT * FROM preferences_memory WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): PreferenceEntity?

    @Query("SELECT * FROM preferences_memory")
    suspend fun getAll(): List<PreferenceEntity>

    @Query("DELETE FROM preferences_memory WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM preferences_memory")
    suspend fun clearAll()
}
