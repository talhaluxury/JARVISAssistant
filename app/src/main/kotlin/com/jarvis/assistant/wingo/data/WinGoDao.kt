package com.jarvis.assistant.wingo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameResultDao {
    /** Returns -1 when the period already exists (duplicate ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(result: GameResultEntity): Long

    @Query("SELECT * FROM game_results ORDER BY period DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<GameResultEntity>

    @Query("SELECT * FROM game_results ORDER BY period DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<GameResultEntity>>

    /** Every stored round, oldest first (used for export). */
    @Query("SELECT * FROM game_results ORDER BY period ASC")
    suspend fun all(): List<GameResultEntity>

    /** One transaction; each element is -1 when that period already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(results: List<GameResultEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM game_results")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM game_results")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(period) FROM game_results")
    suspend fun maxPeriod(): String?

    @Query("DELETE FROM game_results")
    suspend fun clearAll()
}

@Dao
interface PredictionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PredictionRecordEntity): Long

    @Query("SELECT * FROM prediction_records WHERE period = :period LIMIT 1")
    suspend fun byPeriod(period: String): PredictionRecordEntity?

    @Query("UPDATE prediction_records SET actualResult = :actual, correct = :correct WHERE period = :period")
    suspend fun resolve(period: String, actual: String, correct: Boolean): Int

    @Query("SELECT * FROM prediction_records WHERE correct IS NOT NULL ORDER BY period DESC LIMIT :limit")
    suspend fun latestResolved(limit: Int): List<PredictionRecordEntity>

    @Query("SELECT * FROM prediction_records WHERE correct IS NOT NULL AND timestamp >= :since ORDER BY period ASC")
    suspend fun resolvedSince(since: Long): List<PredictionRecordEntity>

    @Query("SELECT COUNT(*) FROM prediction_records")
    suspend fun count(): Int

    @Query("DELETE FROM prediction_records")
    suspend fun clearAll()
}
