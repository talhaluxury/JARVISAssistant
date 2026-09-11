package com.jarvis.assistant.command

import com.jarvis.assistant.accessibility.AutomationStep
import com.jarvis.assistant.accessibility.SwipeDirection
import org.json.JSONArray
import org.json.JSONObject

/** Stable JSON codec for persisted workflow definitions. Only the closed JarvisCommand set is serializable. */
object CommandCodec {
    fun encode(command: JarvisCommand): JSONObject = when (command) {
        is JarvisCommand.OpenApp -> JSONObject().put("type", "OPEN_APP").put("target", command.target)
        is JarvisCommand.OpenSettings -> JSONObject().put("type", "OPEN_SETTINGS").put("target", command.target)
        JarvisCommand.OpenCamera -> JSONObject().put("type", "OPEN_CAMERA")
        is JarvisCommand.OpenBrowser -> JSONObject().put("type", "OPEN_BROWSER").putOpt("url", command.url)
        is JarvisCommand.OpenMaps -> JSONObject().put("type", "OPEN_MAPS").putOpt("query", command.query)
        is JarvisCommand.OpenDialer -> JSONObject().put("type", "OPEN_DIALER").putOpt("number", command.number)
        JarvisCommand.OpenContacts -> JSONObject().put("type", "OPEN_CONTACTS")
        JarvisCommand.OpenCalendar -> JSONObject().put("type", "OPEN_CALENDAR")
        JarvisCommand.OpenClock -> JSONObject().put("type", "OPEN_CLOCK")
        is JarvisCommand.SetAlarm -> JSONObject().put("type", "SET_ALARM").put("hour", command.hour).put("minute", command.minute).putOpt("label", command.label)
        is JarvisCommand.SetTimer -> JSONObject().put("type", "SET_TIMER").put("seconds", command.seconds).putOpt("label", command.label)
        is JarvisCommand.CreateReminder -> JSONObject().put("type", "CREATE_REMINDER").put("text", command.text).put("hour", command.hour).put("minute", command.minute)
        is JarvisCommand.ShareText -> JSONObject().put("type", "SHARE_TEXT").put("text", command.text)
        is JarvisCommand.AdjustVolume -> JSONObject().put("type", "ADJUST_VOLUME").put("direction", if (command.directionUp) "up" else "down").put("stream", command.stream)
        is JarvisCommand.Remember -> JSONObject().put("type", "REMEMBER").put("content", command.content)
        JarvisCommand.GoHome -> JSONObject().put("type", "GO_HOME")
        JarvisCommand.GoBack -> JSONObject().put("type", "GO_BACK")
        JarvisCommand.OpenRecentApps -> JSONObject().put("type", "OPEN_RECENTS")
        JarvisCommand.MediaPlayPause -> JSONObject().put("type", "MEDIA_PLAY_PAUSE")
        JarvisCommand.MediaNext -> JSONObject().put("type", "MEDIA_NEXT")
        JarvisCommand.MediaPrevious -> JSONObject().put("type", "MEDIA_PREVIOUS")
        JarvisCommand.ReadScreen -> JSONObject().put("type", "READ_SCREEN")
        JarvisCommand.ReadNotifications -> JSONObject().put("type", "READ_NOTIFICATIONS")
        JarvisCommand.EnableNotificationAccess -> JSONObject().put("type", "ENABLE_NOTIFICATION_ACCESS")
        is JarvisCommand.SaveTextFile -> JSONObject().put("type", "SAVE_TEXT_FILE").put("filename", command.filename).put("content", command.content)
        is JarvisCommand.Automate -> JSONObject().put("type", "AUTOMATE").putOpt("package", command.packageName).put("steps", JSONArray(command.steps.map { encodeStep(it) }))
        JarvisCommand.EnablePhoneControl -> JSONObject().put("type", "ENABLE_PHONE_CONTROL")
        JarvisCommand.ScrollDown -> JSONObject().put("type", "SCROLL_DOWN")
        JarvisCommand.ScrollUp -> JSONObject().put("type", "SCROLL_UP")
        is JarvisCommand.LongPress -> JSONObject().put("type", "LONG_PRESS").put("target", command.target)
        is JarvisCommand.SearchCurrentApp -> JSONObject().put("type", "SEARCH_CURRENT_APP").put("query", command.query)
        JarvisCommand.TapFirstResult -> JSONObject().put("type", "TAP_FIRST_RESULT")
        is JarvisCommand.SendWhatsAppMessage -> JSONObject().put("type", "SEND_WHATSAPP_MESSAGE").put("contact", command.contact).put("message", command.message)
        JarvisCommand.SendPendingMessage -> JSONObject().put("type", "SEND_PENDING_MESSAGE")
        JarvisCommand.StopAction -> JSONObject().put("type", "STOP")
        JarvisCommand.RunDiagnostic -> JSONObject().put("type", "RUN_DIAGNOSTIC")
        JarvisCommand.MemoryQuery -> JSONObject().put("type", "MEMORY_QUERY")
        is JarvisCommand.ForgetMemory -> JSONObject().put("type", "FORGET_MEMORY").put("query", command.query)
        JarvisCommand.PauseTask -> JSONObject().put("type", "PAUSE_TASK")
        JarvisCommand.ResumeTask -> JSONObject().put("type", "RESUME_TASK")
        JarvisCommand.RetryLastTask -> JSONObject().put("type", "RETRY_LAST_TASK")
        JarvisCommand.ShowLearningStats -> JSONObject().put("type", "SHOW_LEARNING_STATS")
        JarvisCommand.ListInstalledApps -> JSONObject().put("type", "LIST_INSTALLED_APPS")
        JarvisCommand.CloseCurrentApp -> JSONObject().put("type", "CLOSE_APP")
        JarvisCommand.ActivateHud -> JSONObject().put("type", "ACTIVATE_HUD")
        JarvisCommand.StandbyHud -> JSONObject().put("type", "STANDBY_HUD")
        JarvisCommand.ShowSystemStatus -> JSONObject().put("type", "SHOW_SYSTEM_STATUS")
        JarvisCommand.ShowBattery -> JSONObject().put("type", "SHOW_BATTERY")
        JarvisCommand.ShowNetwork -> JSONObject().put("type", "SHOW_NETWORK")
        JarvisCommand.ShowNotificationsHud -> JSONObject().put("type", "SHOW_NOTIFICATIONS")
        JarvisCommand.FullHud -> JSONObject().put("type", "FULL_HUD")
        JarvisCommand.MinimalHud -> JSONObject().put("type", "MINIMAL_HUD")
        JarvisCommand.PowerSavingHud -> JSONObject().put("type", "POWER_SAVING_HUD")
    }

    fun decode(json: JSONObject): JarvisCommand? = CommandEngine.parse(json.toString())

    private fun encodeStep(step: AutomationStep): JSONObject = when(step) {
        is AutomationStep.TapText -> JSONObject().put("action", "tap_text").put("value", step.text)
        is AutomationStep.TapDescription -> JSONObject().put("action", "tap_desc").put("value", step.description)
        is AutomationStep.TypeText -> JSONObject().put("action", "type").put("value", step.text)
        is AutomationStep.Wait -> JSONObject().put("action", "wait").put("value", step.milliseconds)
        AutomationStep.PressBack -> JSONObject().put("action", "back")
        AutomationStep.PressHome -> JSONObject().put("action", "home")
        AutomationStep.ScrollForward -> JSONObject().put("action", "scroll_forward")
        AutomationStep.ScrollBackward -> JSONObject().put("action", "scroll_backward")
        is AutomationStep.Swipe -> JSONObject().put("action", "swipe").put("value", step.direction.name.lowercase())
        is AutomationStep.LongPressText -> JSONObject().put("action", "long_press_text").put("value", step.text)
        is AutomationStep.LongPressDescription -> JSONObject().put("action", "long_press_desc").put("value", step.description)
        AutomationStep.SubmitField -> JSONObject().put("action", "submit_field")
        AutomationStep.TapFirstResult -> JSONObject().put("action", "tap_first_result")
    }
}
