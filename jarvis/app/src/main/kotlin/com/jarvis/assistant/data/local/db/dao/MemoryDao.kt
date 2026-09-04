package com.jarvis.assistant.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.jarvis.assistant.data.local.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}
