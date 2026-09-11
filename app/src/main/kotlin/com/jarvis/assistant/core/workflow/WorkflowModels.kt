package com.jarvis.assistant.core.workflow

import com.jarvis.assistant.command.JarvisCommand

/** Persistent, auditable automation definition. */
data class Workflow(
    val id: String,
    val name: String,
    val trigger: String,
    val steps: List<JarvisCommand>,
    val enabled: Boolean = false,
    val version: Int = 1,
    val requiresApproval: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val changeHistory: List<String> = emptyList(),
    val conditions: List<WorkflowCondition> = emptyList()
)

data class WorkflowCondition(val expression: String)
