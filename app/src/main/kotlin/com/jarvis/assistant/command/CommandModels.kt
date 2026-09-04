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

    object GoHome : JarvisCommand()
    object GoBack : JarvisCommand()
    object OpenRecentApps : JarvisCommand()
    object MediaPlayPause : JarvisCommand()
    object MediaNext : JarvisCommand()
    object MediaPrevious : JarvisCommand()

    /** Reads the visible text on the current screen back, for verification or Q&A about it. */
    object ReadScreen : JarvisCommand()

    /** Saves generated text/code as a real file in the Downloads folder. */
    data class SaveTextFile(val filename: String, val content: String) : JarvisCommand()

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

    // ---------------------------------------------------------------------------------
    // Fast, single-shot "current app" navigation commands. These are deliberately kept
    // separate from Automate: they never require confirmation and never involve the
    // multi-step queue, so "scroll down" / "back" / "search here" answer instantly and
    // always act on whatever app is already in the foreground — never JARVIS itself.
    // ---------------------------------------------------------------------------------

    /** Scrolls the current foreground app's content down/forward. */
    object ScrollDown : JarvisCommand()

    /** Scrolls the current foreground app's content up/backward. */
    object ScrollUp : JarvisCommand()

    /** Long-presses whatever on-screen text/label best matches [target] in the current app. */
    data class LongPress(val target: String) : JarvisCommand()

    /** Finds the search field in whatever app is currently open, types [query], and submits it.
     * Used for "search karo X" once an app like YouTube/Chrome/Play Store is already open. */
    data class SearchCurrentApp(val query: String) : JarvisCommand()

    /** Taps the first result-like item on the current screen — "pehli video chalao",
     * "upar wala kholo", "select the first one". */
    object TapFirstResult : JarvisCommand()

    /**
     * Opens WhatsApp, finds [contact]'s conversation, and types [message] into the input
     * field — but does NOT press send. Sending only happens after the user explicitly
     * confirms ("haan" / "send karo" / "yes"), handled as a separate follow-up SendPendingMessage.
     */
    data class SendWhatsAppMessage(val contact: String, val message: String) : JarvisCommand()

    /** Presses the visible Send button in the current app — used only as the second half of a
     * confirmed WhatsApp/message send, never issued on its own by the AI. */
    object SendPendingMessage : JarvisCommand()

    /** Stops whatever JARVIS is currently doing: speaking, or an in-progress automation. */
    object StopAction : JarvisCommand()
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
    JarvisCommand.GoHome,
    JarvisCommand.GoBack,
    JarvisCommand.OpenRecentApps,
    JarvisCommand.MediaPlayPause,
    JarvisCommand.MediaNext,
    JarvisCommand.MediaPrevious,
    JarvisCommand.ReadScreen,
    JarvisCommand.EnablePhoneControl,
    // Fast current-app navigation is never destructive/sensitive, so it never blocks on
    // confirmation — this is what keeps "scroll down" instant and off the JARVIS activity.
    JarvisCommand.ScrollDown,
    JarvisCommand.ScrollUp,
    is JarvisCommand.LongPress,
    is JarvisCommand.SearchCurrentApp,
    JarvisCommand.TapFirstResult,
    JarvisCommand.StopAction,
    // SendPendingMessage is only ever fired internally, after the user already said "yes" to
    // a pending SendWhatsAppMessage — it is never something the AI/router issues directly.
    JarvisCommand.SendPendingMessage -> false
    is JarvisCommand.OpenDialer,
    is JarvisCommand.SetAlarm,
    is JarvisCommand.SetTimer,
    is JarvisCommand.CreateReminder,
    is JarvisCommand.ShareText,
    is JarvisCommand.SaveTextFile,
    is JarvisCommand.Automate,
    // Sending a message is the one automation step that always needs a real "yes" first.
    is JarvisCommand.SendWhatsAppMessage -> true
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
    JarvisCommand.GoHome -> "Go to the home screen."
    JarvisCommand.GoBack -> "Go back."
    JarvisCommand.OpenRecentApps -> "Open recent apps."
    JarvisCommand.MediaPlayPause -> "Play/pause media."
    JarvisCommand.MediaNext -> "Skip to next track."
    JarvisCommand.MediaPrevious -> "Go to previous track."
    JarvisCommand.ReadScreen -> "Read what's on screen."
    is JarvisCommand.SaveTextFile -> "Save \"$filename\" to Downloads?"
    JarvisCommand.ScrollDown -> "Scroll down."
    JarvisCommand.ScrollUp -> "Scroll up."
    is JarvisCommand.LongPress -> "Long-press \"$target\"."
    is JarvisCommand.SearchCurrentApp -> "Search for \"$query\"."
    JarvisCommand.TapFirstResult -> "Open the first result."
    is JarvisCommand.SendWhatsAppMessage -> "Send \"$message\" to $contact on WhatsApp?"
    JarvisCommand.SendPendingMessage -> "Sending."
    JarvisCommand.StopAction -> "Stopped."
}
