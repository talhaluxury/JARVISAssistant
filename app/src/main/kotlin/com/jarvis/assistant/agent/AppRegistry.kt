package com.jarvis.assistant.agent

import android.content.Context
import android.content.Intent

data class InstalledApp(val label: String, val packageName: String, val launchable: Boolean)

/**
 * Lists launchable apps through the public launcher query instead of QUERY_ALL_PACKAGES.
 * This keeps the app useful while avoiding broad package visibility that can be restricted
 * by Android/Play policy.
 */
class AppRegistry(private val context: Context) {
    fun snapshot(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { resolve ->
                InstalledApp(
                    resolve.loadLabel(pm).toString(),
                    resolve.activityInfo.packageName,
                    true
                )
            }
            .filter { it.label.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun resolve(name: String): InstalledApp? = snapshot().let { apps ->
        apps.firstOrNull { it.label.equals(name.trim(), true) }
            ?: apps.firstOrNull { it.label.contains(name.trim(), true) }
    }

    fun compactPrompt(maxApps: Int = 120): String =
        snapshot().take(maxApps).joinToString("\n") { "- ${it.label} (${it.packageName})" }
}
