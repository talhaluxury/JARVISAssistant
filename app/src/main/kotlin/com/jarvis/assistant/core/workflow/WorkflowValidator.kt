package com.jarvis.assistant.core.workflow

import com.jarvis.assistant.command.JarvisCommand

class WorkflowValidator {
    fun validate(workflow: Workflow): List<String> = buildList {
        if (workflow.name.isBlank()) add("Workflow name is required")
        if (workflow.trigger.isBlank()) add("Trigger is required")
        if (workflow.steps.isEmpty()) add("At least one step is required")
        if (workflow.steps.size > 50) add("Workflow exceeds 50 steps")
        if (workflow.steps.any { it is JarvisCommand.StopAction }) add("STOP cannot be persisted as a workflow step")
    }
}
