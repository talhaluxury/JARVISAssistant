package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.JarvisAccessibilityService

sealed class ExecutionResult {
    data class Success(val message: String) : ExecutionResult()
    data class Failure(val message: String) : ExecutionResult()
}

/**
 * Executes a JarvisCommand using ONLY documented, public Android APIs/Intents,
 * plus (for Automate) the Accessibility Service the user explicitly turned on.
 * No hidden APIs, no security bypasses, no silent background control of the
 * device — every action here is exactly what a user could trigger by tapping
 * through the system UI themselves.
 */
class AndroidActionExecutor(private val context: Context) {

    fun execute(command: JarvisCommand): ExecutionResult = try {
        when (command) {
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
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(name, ignoreCase = true)
        } ?: apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(name, ignoreCase = true)
        }
        val launchIntent = match?.let { pm.getLaunchIntentForPackage(it.packageName) }
            ?: return ExecutionResult.Failure("\"$name\" isn't installed on this phone.")
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
