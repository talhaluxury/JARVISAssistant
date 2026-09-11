package com.jarvis.assistant.core.security

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.core.state.RiskLevel

class RiskEngine {
    fun assess(command: JarvisCommand): RiskLevel = when {
        command is JarvisCommand.SendWhatsAppMessage || command is JarvisCommand.SendPendingMessage ||
            command is JarvisCommand.ForgetMemory -> RiskLevel.HIGH
        command is JarvisCommand.Automate || command.requiresConfirmation() -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}
