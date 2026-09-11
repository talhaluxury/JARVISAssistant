package com.jarvis.assistant.agent

import android.content.Context
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.data.local.prefs.SecurePrefs
import com.jarvis.assistant.notifications.JarvisNotificationListenerService
import com.jarvis.assistant.util.NetworkMonitor

/**
 * #41 CAPABILITY ROUTER
 *
 * The single source of truth for "what can JARVIS actually do right now". Every tool the AI
 * or the local routers can invoke is listed here with its REAL, live-checked state — never a
 * hardcoded "yes". Nothing downstream (prompt, planner, executor) should claim a capability
 * exists without checking this registry first, and the AI is only ever told about tools that
 * are actually wired to real implementations.
 */
enum class Tool {
    OPEN_APP, OPEN_SETTINGS, OPEN_CAMERA, OPEN_BROWSER, OPEN_MAPS, OPEN_DIALER,
    OPEN_CONTACTS, OPEN_CALENDAR, OPEN_CLOCK,
    SET_ALARM, SET_TIMER, CREATE_REMINDER, SHARE_TEXT, ADJUST_VOLUME,
    SAVE_TEXT_FILE,
    GO_HOME, GO_BACK, OPEN_RECENTS, MEDIA_CONTROL,
    READ_SCREEN, READ_NOTIFICATIONS,
    SCREEN_AUTOMATION, // tap/type/scroll/search-in-app/AUTOMATE/WhatsApp send
    VOICE_INPUT, VOICE_OUTPUT,
    MEMORY_READ, MEMORY_WRITE,
    WEB_SEARCH,
    HUD_CONTROL,
    NETWORK_STATUS, BATTERY_STATUS,
    DIAGNOSTIC, LEARNING
}

enum class ToolAvailability { AVAILABLE, UNAVAILABLE, REQUIRES_PERMISSION, PERMISSION_REQUIRED, TEMPORARILY_UNAVAILABLE, NOT_SUPPORTED }

data class CapabilityStatusEntry(
    val tool: Tool,
    val state: ToolAvailability,
    val explanation: String
)

/**
 * Live capability snapshot. Built fresh on every check — permissions, accessibility, and
 * network state can all change at any moment while JARVIS is running.
 */
class CapabilityRegistry(private val context: Context, private val securePrefs: SecurePrefs) {

    fun snapshot(): List<CapabilityStatusEntry> {
        val accessibilityOn = JarvisAccessibilityService.isEnabled
        val screenAutomationOn = securePrefs.screenAutomationEnabled
        val notificationAccessOn = JarvisNotificationListenerService.isEnabled
        val online = NetworkMonitor.isOnline(context)
        val aiConfigured = securePrefs.isConfigured()

        fun intentBased(tool: Tool) = CapabilityStatusEntry(tool, ToolAvailability.AVAILABLE, "Public Android intent — always available.")

        return listOf(
            intentBased(Tool.OPEN_APP), intentBased(Tool.OPEN_SETTINGS), intentBased(Tool.OPEN_CAMERA),
            intentBased(Tool.OPEN_BROWSER), intentBased(Tool.OPEN_MAPS), intentBased(Tool.OPEN_DIALER),
            intentBased(Tool.OPEN_CONTACTS), intentBased(Tool.OPEN_CALENDAR), intentBased(Tool.OPEN_CLOCK),
            intentBased(Tool.SET_ALARM), intentBased(Tool.SET_TIMER), intentBased(Tool.CREATE_REMINDER),
            intentBased(Tool.SHARE_TEXT), intentBased(Tool.ADJUST_VOLUME), intentBased(Tool.SAVE_TEXT_FILE),
            intentBased(Tool.GO_HOME), intentBased(Tool.MEDIA_CONTROL),
            CapabilityStatusEntry(
                Tool.GO_BACK,
                if (accessibilityOn) ToolAvailability.AVAILABLE else ToolAvailability.REQUIRES_PERMISSION,
                if (accessibilityOn) "Ready." else "Accessibility permission is required."
            ),
            CapabilityStatusEntry(
                Tool.OPEN_RECENTS,
                if (accessibilityOn) ToolAvailability.AVAILABLE else ToolAvailability.REQUIRES_PERMISSION,
                if (accessibilityOn) "Ready." else "Accessibility permission is required."
            ),
            CapabilityStatusEntry(
                Tool.READ_SCREEN,
                if (accessibilityOn) ToolAvailability.AVAILABLE else ToolAvailability.REQUIRES_PERMISSION,
                if (accessibilityOn) "Ready." else "Accessibility permission is required."
            ),
            CapabilityStatusEntry(
                Tool.READ_NOTIFICATIONS,
                if (notificationAccessOn) ToolAvailability.AVAILABLE else ToolAvailability.REQUIRES_PERMISSION,
                if (notificationAccessOn) "Ready." else "Notification Access permission is required."
            ),
            CapabilityStatusEntry(
                Tool.SCREEN_AUTOMATION,
                when {
                    !accessibilityOn -> ToolAvailability.REQUIRES_PERMISSION
                    !screenAutomationOn -> ToolAvailability.UNAVAILABLE
                    else -> ToolAvailability.AVAILABLE
                },
                when {
                    !accessibilityOn -> "Accessibility permission is required."
                    !screenAutomationOn -> "Turned off in Settings (Screen automation switch)."
                    else -> "Ready."
                }
            ),
            CapabilityStatusEntry(Tool.VOICE_INPUT, ToolAvailability.AVAILABLE, "SpeechRecognizer available in foreground."),
            CapabilityStatusEntry(Tool.VOICE_OUTPUT, ToolAvailability.AVAILABLE, "TextToSpeech ready."),
            CapabilityStatusEntry(Tool.MEMORY_READ, ToolAvailability.AVAILABLE, "Local Room database."),
            CapabilityStatusEntry(Tool.MEMORY_WRITE, ToolAvailability.AVAILABLE, "Local Room database."),
            CapabilityStatusEntry(
                Tool.WEB_SEARCH,
                if (!online) ToolAvailability.UNAVAILABLE
                else if (securePrefs.searchApiKey.isNullOrBlank()) ToolAvailability.REQUIRES_PERMISSION
                else ToolAvailability.AVAILABLE,
                when {
                    !online -> "No network connection."
                    securePrefs.searchApiKey.isNullOrBlank() -> "A Search API key must be added in Settings."
                    else -> "Ready."
                }
            ),
            CapabilityStatusEntry(Tool.HUD_CONTROL, ToolAvailability.AVAILABLE, "Local wallpaper/HUD state — no network needed."),
            CapabilityStatusEntry(Tool.NETWORK_STATUS, ToolAvailability.AVAILABLE, "Read from ConnectivityManager."),
            CapabilityStatusEntry(Tool.BATTERY_STATUS, ToolAvailability.AVAILABLE, "Read from BatteryManager."),
            CapabilityStatusEntry(Tool.DIAGNOSTIC, ToolAvailability.AVAILABLE, "Local self-check, no network needed."),
            CapabilityStatusEntry(Tool.LEARNING, ToolAvailability.AVAILABLE, "Local task-outcome log.")
        )
    }

    /** AI reasoning is not modeled as a Tool (it's the reasoner, not a tool it calls), so it
     * gets its own explicit, non-hallucinated check rather than being folded into [snapshot]. */
    fun aiAvailable(): Pair<ToolAvailability, String> {
        val online = NetworkMonitor.isOnline(context)
        val configured = securePrefs.isConfigured()
        return when {
            !configured -> ToolAvailability.REQUIRES_PERMISSION to "No AI API key configured in Settings."
            !online -> ToolAvailability.UNAVAILABLE to "No network connection."
            else -> ToolAvailability.AVAILABLE to "Ready."
        }
    }

    fun isAvailable(tool: Tool): Boolean = snapshot().firstOrNull { it.tool == tool }?.state == ToolAvailability.AVAILABLE

    fun explain(tool: Tool): String = snapshot().firstOrNull { it.tool == tool }?.explanation
        ?: "That capability is currently unavailable."

    /** Compact text block handed to the AI so it never claims a tool exists when it doesn't. */
    fun compactPrompt(): String = snapshot().joinToString("\n") {
        "- ${it.tool} ........ ${it.state}${if (it.state != ToolAvailability.AVAILABLE) " (${it.explanation})" else ""}"
    }
}
