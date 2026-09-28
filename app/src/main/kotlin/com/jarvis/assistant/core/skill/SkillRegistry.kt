package com.jarvis.assistant.core.skill

import com.jarvis.assistant.command.JarvisCommand
import java.util.concurrent.ConcurrentHashMap

class SkillRegistry {
    private val skills = ConcurrentHashMap<String, JarvisSkill>()
    fun register(skill: JarvisSkill) { skills[skill.id] = skill }
    fun all(): List<JarvisSkill> = skills.values.sortedBy { it.id }
    fun findFor(command: JarvisCommand): JarvisSkill? = all().firstOrNull { it.supports(command) }
}
