package com.jarvis.assistant.command

import org.json.JSONObject

/**
 * Parses the JSON the AI proposed and turns it into one of the closed set
 * of JarvisCommand types — or null if it doesn't match a known, valid shape.
 * This is the ONLY path from "AI text" to "app does something". Arbitrary
 * AI-generated code is never executed; only these predefined actions are.
 */
object CommandEngine {

    fun parse(commandJson: String?): JarvisCommand? {
        if (commandJson.isNullOrBlank()) return null
        return try {
            val json = JSONObject(commandJson)
            when (json.optString("type").uppercase()) {
                "OPEN_APP" -> json.optString("target").takeIf { it.isNotBlank() }
                    ?.let { JarvisCommand.OpenApp(it) }

                "OPEN_SETTINGS" -> {
                    val target = json.optString("target", "general").lowercase()
                    val valid = if (target in setOf("wifi", "bluetooth", "general")) target else "general"
                    JarvisCommand.OpenSettings(valid)
                }

                "OPEN_CAMERA" -> JarvisCommand.OpenCamera

                "OPEN_BROWSER" -> JarvisCommand.OpenBrowser(json.optString("url").takeIf { it.isNotBlank() })

                "OPEN_MAPS" -> JarvisCommand.OpenMaps(json.optString("query").takeIf { it.isNotBlank() })

                "OPEN_DIALER" -> JarvisCommand.OpenDialer(json.optString("number").takeIf { it.isNotBlank() })

                "OPEN_CONTACTS" -> JarvisCommand.OpenContacts
                "OPEN_CALENDAR" -> JarvisCommand.OpenCalendar
                "OPEN_CLOCK" -> JarvisCommand.OpenClock

                "SET_ALARM" -> {
                    val hour = json.optInt("hour", -1)
                    val minute = json.optInt("minute", -1)
                    if (hour !in 0..23 || minute !in 0..59) return null
                    JarvisCommand.SetAlarm(hour, minute, json.optString("label").takeIf { it.isNotBlank() })
                }

                "SET_TIMER" -> {
                    val seconds = json.optInt("seconds", -1)
                    if (seconds <= 0 || seconds > 24 * 3600) return null
                    JarvisCommand.SetTimer(seconds, json.optString("label").takeIf { it.isNotBlank() })
                }

                "CREATE_REMINDER" -> {
                    val text = json.optString("text").takeIf { it.isNotBlank() } ?: return null
                    val hour = json.optInt("hour", -1)
                    val minute = json.optInt("minute", -1)
                    if (hour !in 0..23 || minute !in 0..59) return null
                    JarvisCommand.CreateReminder(text, hour, minute)
                }

                "SHARE_TEXT" -> json.optString("text").takeIf { it.isNotBlank() }
                    ?.let { JarvisCommand.ShareText(it) }

                "ADJUST_VOLUME" -> {
                    val dir = json.optString("direction", "up").lowercase()
                    if (dir !in setOf("up", "down")) return null
                    JarvisCommand.AdjustVolume(dir == "up", json.optString("stream", "media"))
                }

                "REMEMBER" -> json.optString("content").takeIf { it.isNotBlank() }
                    ?.let { JarvisCommand.Remember(it) }

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
