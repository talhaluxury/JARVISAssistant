package com.jarvis.assistant.agent

import android.content.Context

data class InstalledApp(val label: String, val packageName: String, val launchable: Boolean)

class AppRegistry(private val context: Context) {
    fun snapshot(): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .map { info ->
                InstalledApp(
                    pm.getApplicationLabel(info).toString(),
                    info.packageName,
                    pm.getLaunchIntentForPackage(info.packageName) != null
                )
            }
            .filter { it.label.isNotBlank() }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun resolve(name: String): InstalledApp? = snapshot().let { apps ->
        apps.firstOrNull { it.label.equals(name.trim(), true) && it.launchable }
            ?: apps.firstOrNull { it.label.contains(name.trim(), true) && it.launchable }
    }

    fun compactPrompt(maxApps: Int = 120): String =
        snapshot().filter { it.launchable }.take(maxApps)
            .joinToString("\n") { "- ${it.label} (${it.packageName})" }
}
