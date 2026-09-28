package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.local.db.dao.MemoryDao
import com.jarvis.assistant.data.local.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val dao: MemoryDao) {

    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeAll()

    /** A synchronous snapshot for context-building (ContextManager), as opposed to the Flow
     * used by the Memory screen. */
    suspend fun observeAllSnapshot(): List<MemoryEntity> = dao.getAll()

    suspend fun getAllAsPromptContext(): String {
        val all = dao.getAll()
        if (all.isEmpty()) return ""
        return all.joinToString(separator = "\n") { "- ${it.content}" }
    }

    /** #38 layer metadata: every memory written today is user-approved and user-explicit —
     * the AI never gets a path to write memory on its own. */
    suspend fun remember(content: String, category: String = "general") {
        dao.insert(MemoryEntity(content = content, category = category, source = "user_explicit", confidence = 1.0f, approved = true))
    }

    suspend fun forget(memory: MemoryEntity) = dao.delete(memory)

    /** Supports "forget that" / "delete my saved preferences" style commands: removes memories
     * whose content matches [query], returning how many were removed so the caller can report
     * back accurately rather than claiming success blindly. */
    suspend fun forgetMatching(query: String): Int {
        if (query.isBlank()) return 0
        val all = dao.getAll()
        val matches = all.filter { it.content.contains(query, ignoreCase = true) }
        matches.forEach { dao.delete(it) }
        return matches.size
    }

    suspend fun clearAll() = dao.clearAll()
}
