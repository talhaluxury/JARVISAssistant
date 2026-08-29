package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.local.db.dao.MemoryDao
import com.jarvis.assistant.data.local.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val dao: MemoryDao) {

    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeAll()

    suspend fun getAllAsPromptContext(): String {
        val all = dao.getAll()
        if (all.isEmpty()) return ""
        return all.joinToString(separator = "\n") { "- ${it.content}" }
    }

    suspend fun remember(content: String) {
        dao.insert(MemoryEntity(content = content))
    }

    suspend fun forget(memory: MemoryEntity) = dao.delete(memory)

    suspend fun clearAll() = dao.clearAll()
}
