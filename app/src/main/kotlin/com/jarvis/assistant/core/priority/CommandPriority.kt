package com.jarvis.assistant.core.priority

import com.jarvis.assistant.command.JarvisCommand

enum class CommandPriority(val weight: Int) { BACKGROUND(0), LOW(10), NORMAL(20), HIGH(30), CRITICAL(40), EMERGENCY(50) }

object CommandPriorityResolver {
    fun resolve(command: JarvisCommand): CommandPriority = when (command) {
        JarvisCommand.StopAction, JarvisCommand.PauseTask, JarvisCommand.ResumeTask -> CommandPriority.EMERGENCY
        JarvisCommand.RetryLastTask -> CommandPriority.HIGH
        is JarvisCommand.Automate, is JarvisCommand.SendWhatsAppMessage, JarvisCommand.SendPendingMessage -> CommandPriority.HIGH
        JarvisCommand.RunDiagnostic, JarvisCommand.ShowSystemStatus -> CommandPriority.NORMAL
        else -> CommandPriority.NORMAL
    }
}
