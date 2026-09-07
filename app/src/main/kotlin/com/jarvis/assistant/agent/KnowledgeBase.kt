package com.jarvis.assistant.agent

import com.jarvis.assistant.data.local.db.entity.KnowledgeEntity
import com.jarvis.assistant.data.repository.KnowledgeRepository

/**
 * #45 KNOWLEDGE BASE (retrieval side) — same relevance-scoring approach as
 * [ContextManager]'s memory retrieval: only entries that share a real word with the query are
 * returned, so the AI prompt only ever gets knowledge relevant to what was actually asked
 * rather than the whole knowledge base every turn. Never invents an answer when nothing
 * matches — callers get an empty list and fall back to their normal path (AI reasoning, or a
 * plain "I don't have that" when offline).
 */
class KnowledgeBase(private val repository: KnowledgeRepository) {

    suspend fun retrieve(query: String, limit: Int = 3): List<KnowledgeEntity> {
        val all = repository.all()
        if (all.isEmpty()) return emptyList()
        val queryWords = tokenize(query)
        if (queryWords.isEmpty()) return emptyList()
        return all
            .map { entry -> entry to tokenize("${entry.topic} ${entry.keywords}").intersect(queryWords).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun compactPrompt(entries: List<KnowledgeEntity>): String =
        entries.joinToString("\n\n") { "[${it.category.uppercase()}] ${it.topic}\n${it.content}" }

    suspend fun entryCount(): Int = repository.totalCount()

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
}
