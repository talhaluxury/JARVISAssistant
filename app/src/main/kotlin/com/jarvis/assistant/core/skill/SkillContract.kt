package com.jarvis.assistant.core.skill

import com.jarvis.assistant.agent.CapabilityStatusEntry
import com.jarvis.assistant.command.JarvisCommand

/** Stable contract for future plugins/skills. Android-specific implementations remain behind it. */
interface JarvisSkill {
    val id: String
    val version: Int
    fun capabilities(): List<CapabilityStatusEntry>
    fun supports(command: JarvisCommand): Boolean
}

enum class SkillId {
    VOICE, MEMORY, DEVICE, APP, BROWSER, AUTOMATION, VISION, DIAGNOSTIC, NOTIFICATION, WORKFLOW
}
