package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.data.repository.MemoryRepository
import com.jarvis.assistant.trading.ForexBrainCommandExecutor

/**
 * Local, offline "brain" commands: self-diagnostic, memory recall/forget, and learning stats.
 * Like HudCommandExecutor, these never touch AndroidActionExecutor and never need network.
 * Returns null for anything it doesn't own, so the caller falls through to the normal executor.
 *
 * Forex voice commands (see com.jarvis.assistant.trading.JarvisCommand additions) are delegated
 * to [forexBrainCommandExecutor] rather than handled inline here, since they need the whole
 * trading engine dependency graph (market data, signal/risk engines, journal, settings) rather
 * than this class's own three dependencies — composing a second brain executor keeps that graph
 * out of this file entirely while still presenting one BrainCommandExecutor to the rest of the app.
 */
class BrainCommandExecutor(
    private val diagnosticEngine: DiagnosticEngine,
    private val memoryRepository: MemoryRepository,
    private val learningEngine: LearningEngine,
    private val forexBrainCommandExecutor: ForexBrainCommandExecutor
) {
    suspend fun execute(command: JarvisCommand): String? = when (command) {
        JarvisCommand.RunDiagnostic -> diagnosticEngine.run().toReportText()

        JarvisCommand.MemoryQuery -> {
            val all = memoryRepository.observeAllSnapshot()
            if (all.isEmpty()) "I don't have anything saved in memory yet."
            else "Here's what I remember:\n" + all.joinToString("\n") { "- ${it.content}" }
        }

        is JarvisCommand.ForgetMemory -> {
            if (command.query.isBlank()) {
                memoryRepository.clearAll()
                "All saved memory has been deleted."
            } else {
                val removed = memoryRepository.forgetMatching(command.query)
                if (removed > 0) "Removed $removed matching ${if (removed == 1) "memory" else "memories"}."
                else "I couldn't find any saved memory matching \"${command.query}\"."
            }
        }

        JarvisCommand.ShowLearningStats -> {
            val stats = learningEngine.stats()
            buildString {
                if (stats.totalTasks == 0) {
                    append("No tasks have run yet.")
                } else {
                    appendLine("${stats.totalTasks} tasks run, ${(stats.successRate * 100).toInt()}% success rate.")
                    appendLine("Average execution time: ${stats.averageExecutionTimeMs} ms.")
                    if (stats.mostCommonFailures.isNotEmpty()) {
                        appendLine("Most common failures: " + stats.mostCommonFailures.joinToString(", ") { "${it.first} (${it.second})" })
                    }
                    if (stats.flaggedWorkflows.isNotEmpty()) {
                        appendLine("Flagged for improvement:")
                        stats.flaggedWorkflows.forEach { appendLine("- $it") }
                    }
                }
            }.trim()
        }

        JarvisCommand.ScanForexMarket, is JarvisCommand.AnalyzeForexPair,
        JarvisCommand.ShowOpenForexTrades, JarvisCommand.ShowForexRiskStatus,
        JarvisCommand.WhyNoForexTrade, JarvisCommand.ShowForexPerformance,
        is JarvisCommand.RequestForexTrade, JarvisCommand.ConfirmForexTradeExecution,
        JarvisCommand.CancelPendingForexTrade, JarvisCommand.EnableDemoForexTrading,
        JarvisCommand.EnableLiveForexTrading, JarvisCommand.DisableLiveForexTrading,
        JarvisCommand.PauseForexTrading, JarvisCommand.ForexEmergencyStop,
        JarvisCommand.ResumeForexTrading -> forexBrainCommandExecutor.execute(command)

        else -> null
    }
}
