package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand

enum class TaskStatus { IDLE, PLANNING, EXECUTING, VERIFYING, COMPLETED, FAILED, CANCELLED }

data class AgentTask(
    val id: String,
    val userRequest: String,
    val steps: List<JarvisCommand>,
    val currentStep: Int = 0,
    val status: TaskStatus = TaskStatus.IDLE,
    val completedSteps: List<String> = emptyList(),
    val failedSteps: List<String> = emptyList(),
    val retryCount: Int = 0
)
