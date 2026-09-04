package com.jarvis.assistant.agent

import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.JarvisCommand
import org.json.JSONObject

/** Converts AI proposals into a bounded, validated action plan. The AI never receives an
 * executable callback and never gets to invoke Kotlin/Android APIs directly. */
class AgentPlanner {
    data class Plan(val actions: List<JarvisCommand>)

    fun parsePlan(commandJson: String?): Plan? {
        if (commandJson.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(commandJson)
            if (root.optString("type").uppercase() != "AGENT_PLAN") return@runCatching null
            val array = root.optJSONArray("actions") ?: return@runCatching null
            if (array.length() !in 1..12) return@runCatching null
            val actions = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: return@runCatching null
                    val command = CommandEngine.parse(item.toString()) ?: return@runCatching null
                    if (command is JarvisCommand.StopAction) return@runCatching null
                    add(command)
                }
            }
            Plan(actions)
        }.getOrNull()
    }
}
