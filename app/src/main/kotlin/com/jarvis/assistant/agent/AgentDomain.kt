package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand

/**
 * The specialist "agents" that make up JARVIS. Every command belongs to exactly
 * one domain. This isn't a separate execution path from [AndroidActionExecutor] -
 * it's a routing/labeling layer on top of it, so:
 *  - task logs and the HUD can show which specialist is acting ("COMMUNICATION
 *    AGENT: sending WhatsApp message" instead of an opaque step number),
 *  - each domain can eventually get its own tuned verification/retry rules,
 *  - a future re-planning prompt can be domain-specific ("you are the
 *    Navigation agent, you only handle maps/location commands") instead of one
 *    giant generic prompt for everything.
 */
enum class AgentDomain(val label: String) {
    COMMUNICATION("Communication Agent"),
    NAVIGATION("Navigation Agent"),
    SYSTEM("System Agent"),
    AUTOMATION("Automation Agent"),
    MEDIA("Media Agent"),
    RESEARCH("Research Agent"),
    BRAIN("Brain Agent");

    companion object {
        fun of(command: JarvisCommand): AgentDomain = when (command) {
            is JarvisCommand.SendWhatsAppMessage, JarvisCommand.SendPendingMessage,
            is JarvisCommand.OpenDialer, JarvisCommand.OpenContacts, is JarvisCommand.ShareText ->
                COMMUNICATION

            is JarvisCommand.OpenMaps -> NAVIGATION

            is JarvisCommand.OpenApp, is JarvisCommand.OpenSettings, JarvisCommand.OpenCamera,
            is JarvisCommand.OpenBrowser, JarvisCommand.OpenCalendar, JarvisCommand.OpenClock,
            is JarvisCommand.SetAlarm, is JarvisCommand.SetTimer, is JarvisCommand.CreateReminder,
            is JarvisCommand.AdjustVolume, JarvisCommand.EnablePhoneControl, JarvisCommand.GoHome,
            JarvisCommand.GoBack, JarvisCommand.OpenRecentApps, JarvisCommand.CloseCurrentApp,
            JarvisCommand.EnableNotificationAccess, JarvisCommand.ListInstalledApps,
            is JarvisCommand.SaveTextFile ->
                SYSTEM

            is JarvisCommand.Automate, JarvisCommand.ScrollDown, JarvisCommand.ScrollUp,
            is JarvisCommand.LongPress, is JarvisCommand.SearchCurrentApp,
            JarvisCommand.TapFirstResult ->
                AUTOMATION

            JarvisCommand.MediaPlayPause, JarvisCommand.MediaNext, JarvisCommand.MediaPrevious ->
                MEDIA

            JarvisCommand.ReadScreen, JarvisCommand.ReadNotifications ->
                RESEARCH

            else -> BRAIN
        }
    }
}
