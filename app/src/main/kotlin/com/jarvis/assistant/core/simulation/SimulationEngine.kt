package com.jarvis.assistant.core.simulation

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.describe
import com.jarvis.assistant.command.requiresConfirmation

/** Developer dry-run: produces the exact planned actions without invoking Android APIs. */
class SimulationEngine {
    data class Preview(val command: JarvisCommand, val action: String, val risk: String)
    fun preview(commands: List<JarvisCommand>): List<Preview> = commands.map {
        Preview(it, it.describe(), if (it.requiresConfirmation()) "CONFIRM" else "SAFE")
    }
}
