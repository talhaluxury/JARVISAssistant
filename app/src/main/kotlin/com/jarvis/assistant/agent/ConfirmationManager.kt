package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.data.local.prefs.SecurePrefs

class ConfirmationManager(private val prefs: SecurePrefs) {
    fun required(command: JarvisCommand): Boolean = prefs.confirmEveryAction || command.requiresConfirmation()
}
