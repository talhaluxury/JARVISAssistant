package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.data.repository.MemoryRepository

/**
 * #37 CONTEXT-AWARE INTELLIGENCE
 *
 * Combines: current message + previous command + current task + current app + device state +
 * relevant memory + available capabilities, into one object everything else reads from —
 * instead of every layer (prompt builder, planner, executor) separately reaching into prefs,
 * accessibility state, and memory in an ad hoc way. Memory relevance filtering happens here so
 * irrelevant memories never reach the model: only entries whose content shares real terms with
 * the current message are included, keeping the context proportional to the request.
 */
data class ConversationContext(
    val message: String,
    val previousCommand: JarvisCommand?,
    val currentTaskDescription: String?,
    val phoneContext: PhoneContext,
    val relevantMemory: List<String>,
    val capabilities: List<CapabilityStatusEntry>,
    val intent: ParsedIntent
)

class ContextManager(
    private val phoneContextEngine: PhoneContextEngine,
    private val capabilityRegistry: CapabilityRegistry,
    private val memoryRepository: MemoryRepository
) {
    /** Tracks only what's needed for follow-up resolution — never a full conversation replay. */
    @Volatile private var lastCommand: JarvisCommand? = null
    @Volatile private var currentTaskDescription: String? = null

    fun recordCommand(command: JarvisCommand) {
        lastCommand = command
    }

    fun setCurrentTask(description: String?) {
        currentTaskDescription = description
    }

    suspend fun build(message: String, hasPendingTask: Boolean): ConversationContext {
        val intent = IntentEngine.classify(message, lastCommand, hasPendingTask)
        val relevant = relevantMemories(message)
        return ConversationContext(
            message = message,
            previousCommand = lastCommand,
            currentTaskDescription = currentTaskDescription,
            phoneContext = phoneContextEngine.snapshot(),
            relevantMemory = relevant,
            capabilities = capabilityRegistry.snapshot(),
            intent = intent
        )
    }

    /** Relevance-based retrieval: only memory entries that share a real (3+ letter) word with
     * the current message are used, rather than dumping every stored memory into every prompt. */
    private suspend fun relevantMemories(message: String, limit: Int = 8): List<String> {
        val all = memoryRepository.observeAllSnapshot()
        if (all.isEmpty()) return emptyList()
        val queryWords = tokenize(message)
        if (queryWords.isEmpty()) return all.take(limit).map { it.content }
        val scored = all.map { memory ->
            val memoryWords = tokenize(memory.content)
            val overlap = memoryWords.intersect(queryWords).size
            memory to overlap
        }
        val relevant = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first.content }
        // If nothing scores as relevant, still surface the most recent few — an empty memory
        // context is worse than a small, generic one for a genuinely new topic.
        return (relevant.ifEmpty { all.take(3).map { it.content } }).take(limit)
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
}
