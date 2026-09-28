package com.jarvis.assistant.agent

/**
 * Picks which specialist agent should handle a goal. Deliberately keyword-based
 * rather than an extra AI call just to classify - keeps routing instant and free,
 * and is easy to reason about/extend as more agents are added.
 */
class AgentRouter {
    fun route(goal: String): AgentDefinition {
        val lower = goal.lowercase()
        return AgentDefinitions.ALL
            .filter { it.keywords.isNotEmpty() }
            .maxByOrNull { agent -> agent.keywords.count { lower.contains(it) } }
            ?.takeIf { agent -> agent.keywords.any { lower.contains(it) } }
            ?: AgentDefinitions.GENERAL
    }
}
