package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.AutomationStep
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.data.local.prefs.SecurePrefs
import com.jarvis.assistant.notifications.JarvisNotificationListenerService

sealed class ExecutionResult {
    abstract val message: String
    data class Success(override val message: String) : ExecutionResult()
    data class Failure(override val message: String) : ExecutionResult()
}

/**
 * Executes a JarvisCommand using ONLY documented, public Android APIs/Intents,
 * plus (for Automate) the Accessibility Service the user explicitly turned on.
 * No hidden APIs, no security bypasses, no silent background control of the
 * device — every action here is exactly what a user could trigger by tapping
 * through the system UI themselves.
 */
class AndroidActionExecutor(private val context: Context, private val securePrefs: SecurePrefs) {

    /** Commands that reach into another app's screen — gated by the "Screen automation" switch
     * in Settings, independent of whether Accessibility itself is on. */
    private val screenAutomationCommands = setOf(
        JarvisCommand.ScrollDown::class, JarvisCommand.ScrollUp::class,
        JarvisCommand.LongPress::class, JarvisCommand.SearchCurrentApp::class,
        JarvisCommand.TapFirstResult::class, JarvisCommand.SendWhatsAppMessage::class,
        JarvisCommand.SendPendingMessage::class, JarvisCommand.Automate::class
    )

    fun execute(command: JarvisCommand): ExecutionResult = try {
        if (command::class in screenAutomationCommands && !securePrefs.screenAutomationEnabled) {
            ExecutionResult.Failure("Screen automation is turned off in Settings.")
        } else when (command) {
            is JarvisCommand.OpenApp -> openApp(command.target)
            is JarvisCommand.OpenSettings -> openSettings(command.target)
            JarvisCommand.OpenCamera -> launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            is JarvisCommand.OpenBrowser -> openBrowser(command.url)
            is JarvisCommand.OpenMaps -> openMaps(command.query)
            is JarvisCommand.OpenDialer -> openDialer(command.number)
            JarvisCommand.OpenContacts -> launch(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
            JarvisCommand.OpenCalendar -> openCalendar()
            JarvisCommand.OpenClock -> launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            is JarvisCommand.SetAlarm -> setAlarm(command)
            is JarvisCommand.SetTimer -> setTimer(command)
            is JarvisCommand.CreateReminder -> createReminder(command)
            is JarvisCommand.ShareText -> shareText(command.text)
            is JarvisCommand.AdjustVolume -> adjustVolume(command)
            is JarvisCommand.Remember -> ExecutionResult.Success("Remembered.")
            JarvisCommand.EnablePhoneControl -> openAccessibilitySettings()
            is JarvisCommand.Automate -> runAutomation(command)
            JarvisCommand.GoHome -> goHome()
            JarvisCommand.GoBack -> requireAccessibility { JarvisAccessibilityService.pressBack() }
            JarvisCommand.OpenRecentApps -> requireAccessibility { JarvisAccessibilityService.openRecents() }
            JarvisCommand.MediaPlayPause -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            JarvisCommand.MediaNext -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            JarvisCommand.MediaPrevious -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            JarvisCommand.ReadScreen -> readScreen()
            JarvisCommand.ReadNotifications -> readNotifications()
            JarvisCommand.EnableNotificationAccess -> openNotificationAccessSettings()
            is JarvisCommand.SaveTextFile -> saveTextFile(command)
            JarvisCommand.ScrollDown -> requireAccessibility { JarvisAccessibilityService.scrollDown() }
            JarvisCommand.ScrollUp -> requireAccessibility { JarvisAccessibilityService.scrollUp() }
            is JarvisCommand.LongPress -> requireAccessibility { JarvisAccessibilityService.longPress(command.target) }
            is JarvisCommand.SearchCurrentApp -> requireAccessibility { JarvisAccessibilityService.searchCurrentApp(command.query) }
            JarvisCommand.TapFirstResult -> requireAccessibility { JarvisAccessibilityService.tapFirstResult() }
            is JarvisCommand.SendWhatsAppMessage -> sendWhatsAppMessage(command)
            JarvisCommand.SendPendingMessage -> requireAccessibility { JarvisAccessibilityService.tapSendButton() }
            JarvisCommand.StopAction -> {
                JarvisAccessibilityService.cancelQueue()
                ExecutionResult.Success("Stopped.")
            }
            // HUD commands are always intercepted and handled by HudCommandExecutor before
            // reaching this executor (see AssistantViewModel). These branches only exist to
            // satisfy the compiler's exhaustiveness check and are not expected to run.
            JarvisCommand.ActivateHud, JarvisCommand.StandbyHud, JarvisCommand.FullHud,
            JarvisCommand.MinimalHud, JarvisCommand.PowerSavingHud, JarvisCommand.ShowBattery,
            JarvisCommand.ShowNetwork, JarvisCommand.ShowNotificationsHud, JarvisCommand.ShowSystemStatus ->
                ExecutionResult.Success(command.describe())
        }
    } catch (e: ActivityNotFoundException) {
        ExecutionResult.Failure("No app found to handle that action.")
    } catch (e: SecurityException) {
        ExecutionResult.Failure("Permission needed for that action.")
    } catch (e: Exception) {
        ExecutionResult.Failure("Couldn't complete that action: ${e.message}")
    }

    private fun launch(intent: Intent): ExecutionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ExecutionResult.Success("Done.")
    }

    private fun openApp(name: String): ExecutionResult {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(query, 0)
        val match = matches.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: matches.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return ExecutionResult.Failure("\"$name\" isn't installed on this phone.")
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: Intent(query).setPackage(match.activityInfo.packageName)
        return launch(launchIntent)
    }

    private fun openSettings(target: String): ExecutionResult {
        val action = when (target) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return launch(Intent(action))
    }

    private fun openBrowser(url: String?): ExecutionResult {
        val target = url?.takeIf { it.isNotBlank() } ?: "https://www.google.com"
        val fixed = if (target.startsWith("http")) target else "https://$target"
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(fixed)))
    }

    private fun openMaps(query: String?): ExecutionResult {
        val uri = if (query.isNullOrBlank()) Uri.parse("geo:0,0") else Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        return launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
    }

    private fun openDialer(number: String?): ExecutionResult {
        val uri = if (number.isNullOrBlank()) Uri.parse("tel:") else Uri.parse("tel:${Uri.encode(number)}")
        return launch(Intent(Intent.ACTION_DIAL, uri))
    }

    private fun openCalendar(): ExecutionResult {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
        return launch(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun setAlarm(command: JarvisCommand.SetAlarm): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return launch(intent)
    }

    private fun setTimer(command: JarvisCommand.SetTimer): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
            command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        return launch(intent)
    }

    private fun createReminder(command: JarvisCommand.CreateReminder): ExecutionResult {
        // Uses the same system Alarm/Clock reminder surface as SET_ALARM, labeled with the
        // reminder text, since that's the officially supported cross-device reminder hook.
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, command.text)
        }
        return launch(intent)
    }

    private fun shareText(text: String): ExecutionResult {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return launch(Intent.createChooser(intent, "Share via").also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun adjustVolume(command: JarvisCommand.AdjustVolume): ExecutionResult {
        val am = ContextCompat.getSystemService(context, AudioManager::class.java)
            ?: return ExecutionResult.Failure("Volume control unavailable.")
        val direction = if (command.directionUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return ExecutionResult.Success("Volume adjusted.")
    }

    private fun openAccessibilitySettings(): ExecutionResult =
        launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun goHome(): ExecutionResult =
        launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    /** Back/Recents need the Accessibility Service (there's no plain Intent for them). */
    private fun requireAccessibility(action: () -> Boolean): ExecutionResult {
        if (!JarvisAccessibilityService.isEnabled) {
            openAccessibilitySettings()
            return ExecutionResult.Failure(
                "Phone control isn't turned on yet. Find JARVIS in the Accessibility list and switch it on, then try again."
            )
        }
        return if (action()) ExecutionResult.Success("Done.") else ExecutionResult.Failure("Couldn't complete that action.")
    }

    private fun dispatchMediaKey(keyCode: Int): ExecutionResult {
        val am = ContextCompat.getSystemService(context, AudioManager::class.java)
            ?: return ExecutionResult.Failure("Media control unavailable.")
        val eventTime = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        return ExecutionResult.Success("Done.")
    }

    private fun readNotifications(): ExecutionResult {
        if (!JarvisNotificationListenerService.isEnabled) {
            return ExecutionResult.Failure("Notification access is not enabled. Open JARVIS Notification Access in Android Settings first.")
        }
        return ExecutionResult.Success(JarvisNotificationListenerService.summary())
    }

    private fun openNotificationAccessSettings(): ExecutionResult =
        launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun readScreen(): ExecutionResult {
        if (!JarvisAccessibilityService.isEnabled) {
            openAccessibilitySettings()
            return ExecutionResult.Failure(
                "Phone control isn't turned on yet. Find JARVIS in the Accessibility list and switch it on, then try again."
            )
        }
        val text = JarvisAccessibilityService.readVisibleText()
        return if (text.isNullOrBlank()) ExecutionResult.Failure("Nothing readable is visible right now.")
        else ExecutionResult.Success(text)
    }

    private fun saveTextFile(command: JarvisCommand.SaveTextFile): ExecutionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ExecutionResult.Failure("Saving files needs Android 10 or newer.")
        }
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, command.filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(command.filename))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ExecutionResult.Failure("Couldn't create the file.")
            resolver.openOutputStream(uri)?.use { it.write(command.content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExecutionResult.Success("Saved ${command.filename} to Downloads.")
        } catch (e: Exception) {
            ExecutionResult.Failure("Couldn't save the file: ${e.message}")
        }
    }

    private fun mimeTypeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "py" -> "text/x-python"
        "kt", "java" -> "text/x-java-source"
        else -> "text/plain"
    }

    /**
     * Opens WhatsApp, searches for [command.contact], opens the first matching conversation,
     * and types [command.message] into the input field — WITHOUT sending it. This command
     * only ever runs after the caller has already confirmed with the user; the actual tap on
     * "Send" is a separate SendPendingMessage the caller issues once the user says yes.
     */
    private fun sendWhatsAppMessage(command: JarvisCommand.SendWhatsAppMessage): ExecutionResult {
        if (!JarvisAccessibilityService.isEnabled) {
            openAccessibilitySettings()
            return ExecutionResult.Failure(
                "Phone control isn't turned on yet. Find JARVIS in the Accessibility list and switch it on, then try again."
            )
        }
        val pm = context.packageManager
        val whatsappPackage = listOf("com.whatsapp", "com.whatsapp.w4b")
            .firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: return ExecutionResult.Failure("WhatsApp isn't installed on this phone.")
        pm.getLaunchIntentForPackage(whatsappPackage)?.let { launch(it) }

        val steps = listOf(
            AutomationStep.Wait(900),
            AutomationStep.TapDescription("Search"),
            AutomationStep.Wait(400),
            AutomationStep.TypeText(command.contact),
            AutomationStep.Wait(700),
            AutomationStep.TapFirstResult,
            AutomationStep.Wait(900),
            AutomationStep.TypeText(command.message)
        )
        JarvisAccessibilityService.enqueue(steps)
        return ExecutionResult.Success("Message ready for ${command.contact}.")
    }

    private fun runAutomation(command: JarvisCommand.Automate): ExecutionResult {
        if (!JarvisAccessibilityService.isEnabled) {
            openAccessibilitySettings()
            return ExecutionResult.Failure(
                "Phone control isn't turned on yet. Find JARVIS in the Accessibility list and switch it on, then try again."
            )
        }
        command.packageName?.let { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(it) }
        }
        JarvisAccessibilityService.enqueue(command.steps)
        return ExecutionResult.Success("Working on it.")
    }
}
