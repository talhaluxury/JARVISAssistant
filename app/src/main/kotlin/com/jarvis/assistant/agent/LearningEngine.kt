package com.jarvis.assistant.agent

import com.jarvis.assistant.data.local.db.entity.TaskOutcomeEntity
import com.jarvis.assistant.data.repository.BrainRepository

data class LearningStats(
    val totalTasks: Int,
    val successRate: Float,
    val averageExecutionTimeMs: Long,
    val mostCommonFailures: List<Pair<String, Int>>,
    val flaggedWorkflows: List<String>
)

/**
 * #44 LEARNING / EVALUATION ENGINE
 *
 * Records every task outcome (command/intent/steps/result/failure reason/execution time) and
 * surfaces recurring problems. Deliberately data-only: JARVIS may learn from stored outcomes
 * (e.g. "this workflow keeps failing, flag it"), but it NEVER rewrites its own source or
 * bypasses a restriction because of what it learns here — the only output of this engine is
 * information for the user/dev, and (optionally) input the AI is told about in plain text.
 */
class LearningEngine(private val brainRepository: BrainRepository) {

    suspend fun recordOutcome(
        userRequest: String, intentCategory: String, stepCount: Int,
        success: Boolean, failureReason: String?, executionTimeMs: Long
    ) {
        brainRepository.logTaskOutcome(userRequest, intentCategory, stepCount, success, failureReason, executionTimeMs)
        brainRepository.logEvent(
            "TASK_ENGINE",
            "Task ${if (success) "succeeded" else "failed"}: \"$userRequest\"${failureReason?.let { " ($it)" } ?: ""}"
        )
    }

    suspend fun stats(sampleSize: Int = 200): LearningStats {
        val outcomes = brainRepository.recentOutcomes(sampleSize)
        if (outcomes.isEmpty()) {
            return LearningStats(0, 1f, 0L, emptyList(), emptyList())
        }
        val successCount = outcomes.count { it.success }
        val successRate = successCount.toFloat() / outcomes.size
        val avgTime = outcomes.map { it.executionTimeMs }.average().toLong()
        val failureCounts = outcomes.filter { !it.success }
            .groupingBy { it.failureReason ?: "Unknown failure" }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        return LearningStats(
            totalTasks = outcomes.size,
            successRate = successRate,
            averageExecutionTimeMs = avgTime,
            mostCommonFailures = failureCounts,
            flaggedWorkflows = flagRecurringFailures(outcomes)
        )
    }

    /** A workflow (same normalized request text) that has failed 2+ times recently gets
     * flagged for improvement, per the spec's "element selector changes repeatedly" example. */
    private fun flagRecurringFailures(outcomes: List<TaskOutcomeEntity>, threshold: Int = 2): List<String> =
        outcomes.filter { !it.success }
            .groupBy { it.userRequest.trim().lowercase() }
            .filter { it.value.size >= threshold }
            .map { (request, failures) -> "\"$request\" has failed ${failures.size} times (${failures.mapNotNull { it.failureReason }.distinct().joinToString(", ").ifBlank { "no consistent reason recorded" }})" }
}
