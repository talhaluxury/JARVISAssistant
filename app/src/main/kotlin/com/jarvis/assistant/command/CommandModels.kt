package com.jarvis.assistant.command

import com.jarvis.assistant.accessibility.AutomationStep

/** The closed set of actions the app is willing to execute. Nothing else runs, ever. */
sealed class JarvisCommand {
    data class OpenApp(val target: String) : JarvisCommand()
    data class OpenSettings(val target: String) : JarvisCommand()
    object OpenCamera : JarvisCommand()
    data class OpenBrowser(val url: String?) : JarvisCommand()
    data class OpenMaps(val query: String?) : JarvisCommand()
    data class OpenDialer(val number: String?) : JarvisCommand()
    object OpenContacts : JarvisCommand()
    object OpenCalendar : JarvisCommand()
    object OpenClock : JarvisCommand()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : JarvisCommand()
    data class SetTimer(val seconds: Int, val label: String?) : JarvisCommand()
    data class CreateReminder(val text: String, val hour: Int, val minute: Int) : JarvisCommand()
    data class ShareText(val text: String) : JarvisCommand()
    data class AdjustVolume(val directionUp: Boolean, val stream: String) : JarvisCommand()
    data class Remember(val content: String) : JarvisCommand()

    /**
     * Full on-screen automation inside any app: launches [packageName] (if given) then
     * carries out [steps] one at a time using the Accessibility Service — tapping visible
     * buttons/text, typing into visible fields, going back, scrolling. This is the most
     * powerful command JARVIS has, so it always requires the user's explicit confirmation,
     * and it silently does nothing if the user hasn't turned the Accessibility Service on.
     */
    data class Automate(val packageName: String?, val steps: List<AutomationStep>) : JarvisCommand()

    /** Sends the user to the system screen where they can turn the Accessibility Service on. */
    object EnablePhoneControl : JarvisCommand()
}

/** Whether a command needs an explicit "yes, do it" from the user before executing. */
fun JarvisCommand.requiresConfirmation(): Boolean = when (this) {
    is JarvisCommand.OpenApp,
    is JarvisCommand.OpenSettings,
    JarvisCommand.OpenCamera,
    is JarvisCommand.OpenBrowser,
    is JarvisCommand.OpenMaps,
    JarvisCommand.OpenContacts,
    JarvisCommand.OpenCalendar,
    JarvisCommand.OpenClock,
    is JarvisCommand.AdjustVolume,
    is JarvisCommand.Remember,
    JarvisCommand.EnablePhoneControl -> false
    is JarvisCommand.OpenDialer,
    is JarvisCommand.SetAlarm,
    is JarvisCommand.SetTimer,
    is JarvisCommand.CreateReminder,
    is JarvisCommand.ShareText,
    is JarvisCommand.Automate -> true
}

fun JarvisCommand.describe(): String = when (this) {
    is JarvisCommand.OpenApp -> "Open $target?"
    is JarvisCommand.OpenSettings -> "Open $target settings?"
    JarvisCommand.OpenCamera -> "Open the camera?"
    is JarvisCommand.OpenBrowser -> "Open the browser${url?.let { " to $it" } ?: ""}?"
    is JarvisCommand.OpenMaps -> "Open Maps${query?.let { " for $it" } ?: ""}?"
    is JarvisCommand.OpenDialer -> "Open the dialer${number?.let { " to call $it" } ?: ""}?"
    JarvisCommand.OpenContacts -> "Open Contacts?"
    JarvisCommand.OpenCalendar -> "Open Calendar?"
    JarvisCommand.OpenClock -> "Open Clock?"
    is JarvisCommand.SetAlarm -> "Set an alarm for %02d:%02d%s?".format(hour, minute, label?.let { " ($it)" } ?: "")
    is JarvisCommand.SetTimer -> "Start a $seconds second timer${label?.let { " ($it)" } ?: ""}?"
    is JarvisCommand.CreateReminder -> "Create reminder \"$text\" at %02d:%02d?".format(hour, minute)
    is JarvisCommand.ShareText -> "Share this text?"
    is JarvisCommand.AdjustVolume -> "Turn $stream volume ${if (directionUp) "up" else "down"}?"
    is JarvisCommand.Remember -> "Remembered."
    is JarvisCommand.Automate -> "Perform ${steps.size} action(s)${packageName?.let { " in $it" } ?: ""}?"
    JarvisCommand.EnablePhoneControl -> "Open Accessibility settings to turn on phone control?"
}
