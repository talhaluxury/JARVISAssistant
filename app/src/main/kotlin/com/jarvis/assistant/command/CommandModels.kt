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
    object ReadNotifications : JarvisCommand()
    object EnableNotificationAccess : JarvisCommand()

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

    // ---------------------------------------------------------------------------------
    // Brain commands: local-only, never touch AndroidActionExecutor. Handled by
    // BrainCommandExecutor, intercepted in the ViewModel the same way HUD commands are.
    // ---------------------------------------------------------------------------------

    /** #48 SELF-DIAGNOSTIC BRAIN — "run JARVIS diagnostic". */
    object RunDiagnostic : JarvisCommand()

    /** #38 MEMORY MANAGEMENT — "what do you remember about me?" */
    object MemoryQuery : JarvisCommand()

    /** #38 MEMORY MANAGEMENT — "forget that" / "delete my saved preferences". A blank query
     * clears every stored memory; a non-blank query removes only matching entries. */
    data class ForgetMemory(val query: String) : JarvisCommand()

    /** #53 INTERRUPTIONS — pause the in-progress multi-step task. */
    object PauseTask : JarvisCommand()

    /** #53 INTERRUPTIONS — resume a paused task. */
    object ResumeTask : JarvisCommand()

    /** #46 CONVERSATIONAL INTELLIGENCE — "phir se karo" / "try again": re-run the last
     * command or task exactly as it last ran. */
    object RetryLastTask : JarvisCommand()

    /** #56 CONTINUOUS IMPROVEMENT DASHBOARD — summarized learning/task stats, spoken/shown as text. */
    object ShowLearningStats : JarvisCommand()

    /** #10 APP INTELLIGENCE — "which apps are installed?" / "find the browser". */
    object ListInstalledApps : JarvisCommand()

    /** #10 APP INTELLIGENCE — "close the current app if possible". Android gives a regular
     * app no public API to force-close another app (that's a deliberate platform restriction,
     * not a gap here) — the closest legitimate alternative is returning to the home screen,
     * and JARVIS says so plainly rather than claiming the app was actually closed. */
    object CloseCurrentApp : JarvisCommand()

    // HUD-only commands are local, fast and do not require AI/network access.
    object ActivateHud : JarvisCommand()
    object StandbyHud : JarvisCommand()
    object ShowSystemStatus : JarvisCommand()
    object ShowBattery : JarvisCommand()
    object ShowNetwork : JarvisCommand()
    object ShowNotificationsHud : JarvisCommand()
    object FullHud : JarvisCommand()
    object MinimalHud : JarvisCommand()
    object PowerSavingHud : JarvisCommand()
}

/** Local-only "brain" commands that never touch AndroidActionExecutor for real — they must be
 * intercepted (BrainCommandExecutor) before reaching the executor, so they can never appear
 * inside an AgentPlanner plan (see AgentPlanner.parsePlan). */
fun JarvisCommand.isBrainOnly(): Boolean = this is JarvisCommand.RunDiagnostic ||
    this is JarvisCommand.MemoryQuery || this is JarvisCommand.ForgetMemory ||
    this is JarvisCommand.PauseTask || this is JarvisCommand.ResumeTask ||
    this is JarvisCommand.RetryLastTask || this is JarvisCommand.ShowLearningStats

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
    JarvisCommand.ReadNotifications,
    JarvisCommand.EnableNotificationAccess,
    JarvisCommand.EnablePhoneControl,
    // Fast current-app navigation is never destructive/sensitive, so it never blocks on
    // confirmation — this is what keeps "scroll down" instant and off the JARVIS activity.
    JarvisCommand.ScrollDown,
    JarvisCommand.ScrollUp,
    is JarvisCommand.LongPress,
    is JarvisCommand.SearchCurrentApp,
    JarvisCommand.TapFirstResult,
    JarvisCommand.StopAction,
    JarvisCommand.ActivateHud,
    JarvisCommand.StandbyHud,
    JarvisCommand.ShowSystemStatus,
    JarvisCommand.ShowBattery,
    JarvisCommand.ShowNetwork,
    JarvisCommand.ShowNotificationsHud,
    JarvisCommand.FullHud,
    JarvisCommand.MinimalHud,
    JarvisCommand.PowerSavingHud,
    // SendPendingMessage is only ever fired internally, after the user already said "yes" to
    // a pending SendWhatsAppMessage — it is never something the AI/router issues directly.
    JarvisCommand.SendPendingMessage,
    JarvisCommand.RunDiagnostic,
    JarvisCommand.MemoryQuery,
    JarvisCommand.PauseTask,
    JarvisCommand.ResumeTask,
    JarvisCommand.RetryLastTask,
    JarvisCommand.ShowLearningStats,
    JarvisCommand.ListInstalledApps,
    JarvisCommand.CloseCurrentApp -> false
    is JarvisCommand.OpenDialer,
    is JarvisCommand.SetAlarm,
    is JarvisCommand.SetTimer,
    is JarvisCommand.CreateReminder,
    is JarvisCommand.ShareText,
    is JarvisCommand.SaveTextFile,
    is JarvisCommand.Automate,
    // Deleting stored memory is irreversible, same tier as sending a message.
    is JarvisCommand.ForgetMemory,
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
    JarvisCommand.ReadNotifications -> "Read recent notifications."
    JarvisCommand.EnableNotificationAccess -> "Open Notification Access settings?"
    is JarvisCommand.SaveTextFile -> "Save \"$filename\" to Downloads?"
    JarvisCommand.ScrollDown -> "Scroll down."
    JarvisCommand.ScrollUp -> "Scroll up."
    is JarvisCommand.LongPress -> "Long-press \"$target\"."
    is JarvisCommand.SearchCurrentApp -> "Search for \"$query\"."
    JarvisCommand.TapFirstResult -> "Open the first result."
    is JarvisCommand.SendWhatsAppMessage -> "Send \"$message\" to $contact on WhatsApp?"
    JarvisCommand.SendPendingMessage -> "Sending."
    JarvisCommand.StopAction -> "Stopped."
    JarvisCommand.RunDiagnostic -> "Run a full system diagnostic."
    JarvisCommand.MemoryQuery -> "Recall what's stored in memory."
    is JarvisCommand.ForgetMemory -> if (query.isBlank()) "Delete ALL saved memory?" else "Delete saved memory matching \"$query\"?"
    JarvisCommand.PauseTask -> "Pause the current task."
    JarvisCommand.ResumeTask -> "Resume the paused task."
    JarvisCommand.RetryLastTask -> "Retry the last task."
    JarvisCommand.ShowLearningStats -> "Show task performance stats."
    JarvisCommand.ListInstalledApps -> "List installed apps."
    JarvisCommand.CloseCurrentApp -> "Return to the home screen."
    JarvisCommand.ActivateHud -> "Holographic interface activated."
    JarvisCommand.StandbyHud -> "HUD standby activated."
    JarvisCommand.ShowSystemStatus -> "System status displayed."
    JarvisCommand.ShowBattery -> "Battery status displayed."
    JarvisCommand.ShowNetwork -> "Network status displayed."
    JarvisCommand.ShowNotificationsHud -> "Notification HUD displayed."
    JarvisCommand.FullHud -> "Full HUD activated."
    JarvisCommand.MinimalHud -> "Minimal HUD activated."
    JarvisCommand.PowerSavingHud -> "Power-saving HUD activated."
}
