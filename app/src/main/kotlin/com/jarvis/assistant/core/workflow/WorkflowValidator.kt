package com.jarvis.assistant.core.workflow

import com.jarvis.assistant.command.JarvisCommand

class WorkflowValidator {
    fun validate(workflow: Workflow): List<String> = buildList {
        if (workflow.name.isBlank()) add("Workflow name is required")
        if (workflow.trigger.isBlank()) add("Trigger is required")
        if (workflow.steps.isEmpty()) add("At least one step is required")
        if (workflow.steps.size > 50) add("Workflow exceeds 50 steps")
        if (workflow.steps.any { it is JarvisCommand.StopAction }) add("STOP cannot be persisted as a workflow step")
        if (workflow.steps.any { it.isForexCommand() }) {
            add("Forex trading commands cannot be saved into a workflow — they must go through the live voice confirmation flow each time, never an unattended replay.")
        }
    }

    private fun JarvisCommand.isForexCommand(): Boolean = this is JarvisCommand.ScanForexMarket ||
        this is JarvisCommand.AnalyzeForexPair || this is JarvisCommand.ShowOpenForexTrades ||
        this is JarvisCommand.ShowForexRiskStatus || this is JarvisCommand.WhyNoForexTrade ||
        this is JarvisCommand.ShowForexPerformance || this is JarvisCommand.RequestForexTrade ||
        this is JarvisCommand.ConfirmForexTradeExecution || this is JarvisCommand.CancelPendingForexTrade ||
        this is JarvisCommand.EnableDemoForexTrading || this is JarvisCommand.EnableLiveForexTrading ||
        this is JarvisCommand.DisableLiveForexTrading || this is JarvisCommand.PauseForexTrading ||
        this is JarvisCommand.ForexEmergencyStop || this is JarvisCommand.ResumeForexTrading
}
