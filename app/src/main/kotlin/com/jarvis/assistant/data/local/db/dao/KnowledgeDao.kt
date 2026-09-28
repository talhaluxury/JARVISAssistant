package com.jarvis.assistant.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.data.local.db.entity.KnowledgeEntity

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<KnowledgeEntity>)

    @Query("SELECT * FROM knowledge_entries")
    suspend fun getAll(): List<KnowledgeEntity>

    @Query("SELECT * FROM knowledge_entries WHERE category = :category")
    suspend fun byCategory(category: String): List<KnowledgeEntity>

    @Query("SELECT COUNT(*) FROM knowledge_entries WHERE source = 'bundled'")
    suspend fun bundledCount(): Int

    @Query("SELECT COUNT(*) FROM knowledge_entries")
    suspend fun totalCount(): Int

    @Query("DELETE FROM knowledge_entries WHERE source = 'custom'")
    suspend fun clearCustom()
}
