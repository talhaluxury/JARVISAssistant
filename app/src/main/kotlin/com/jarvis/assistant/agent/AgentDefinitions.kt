package com.jarvis.assistant.agent

/**
 * A focused "specialist" persona the autonomous orchestrator can delegate a
 * goal to. Every agent still uses the exact same closed action vocabulary
 * (CommandEngine/AndroidActionExecutor) - the only thing that differs is the
 * system-prompt framing, so the model stays focused on the kind of task at
 * hand instead of one giant do-everything prompt trying to cover every case.
 */
data class AgentDefinition(
    val id: String,
    val displayName: String,
    val personaPrompt: String,
    val keywords: List<String>
)

object AgentDefinitions {
    val MESSAGING = AgentDefinition(
        id = "messaging",
        displayName = "Messaging Agent",
        personaPrompt = """
            You are JARVIS's Messaging specialist for this task: reaching people through calls,
            WhatsApp, and contacts. Prefer the most direct action available (e.g.
            SEND_WHATSAPP_MESSAGE over opening the app and typing manually). Always confirm the
            exact contact name and message content are unambiguous before sending anything.
        """.trimIndent(),
        keywords = listOf("whatsapp", "message", "text", "call", "contact", "dial", "sms")
    )

    val NAVIGATION = AgentDefinition(
        id = "navigation",
        displayName = "Navigation Agent",
        personaPrompt = """
            You are JARVIS's Navigation specialist for this task: maps, directions, and places.
            Use OPEN_MAPS with a clear query (address, place name, or "current location" phrasing)
            rather than guessing coordinates.
        """.trimIndent(),
        keywords = listOf("map", "direction", "navigate", "route", "location", "where is", "distance")
    )

    val SYSTEM = AgentDefinition(
        id = "system",
        displayName = "System Agent",
        personaPrompt = """
            You are JARVIS's System specialist for this task: alarms, timers, reminders, volume,
            settings, and device diagnostics. Double-check numeric values (times, durations) make
            sense before proposing the action.
        """.trimIndent(),
        keywords = listOf("alarm", "timer", "reminder", "volume", "wifi", "bluetooth", "setting", "diagnostic", "battery")
    )

    val RESEARCH = AgentDefinition(
        id = "research",
        displayName = "Research Agent",
        personaPrompt = """
            You are JARVIS's Research specialist for this task: browsing, reading the current
            screen, and reading notifications. Summarize what you find concisely instead of
            reading everything back verbatim.
        """.trimIndent(),
        keywords = listOf("search", "browse", "website", "read screen", "notification", "news", "look up")
    )

    val GENERAL = AgentDefinition(
        id = "general",
        displayName = "General Agent",
        personaPrompt = """
            You are JARVIS's General specialist, handling anything that doesn't clearly belong to
            a more specific area: opening apps, basic navigation of the phone (home/back/recents),
            and everyday multi-step tasks.
        """.trimIndent(),
        keywords = emptyList()
    )

    val ALL = listOf(MESSAGING, NAVIGATION, SYSTEM, RESEARCH, GENERAL)
}
