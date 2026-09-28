package com.jarvis.assistant.core.state

import com.jarvis.assistant.agent.CapabilityRegistry
import com.jarvis.assistant.agent.TaskStatus
import com.jarvis.assistant.util.NetworkMonitor
import android.content.Context

data class SystemSnapshot(
    val ai: String,
    val memory: String,
    val voice: String,
    val automation: String,
    val accessibility: String,
    val hud: String,
    val wallpaper: String,
    val network: String,
    val activeTasks: Int,
    val failedTasks: Int,
    val health: String
)

class SystemSnapshotProvider(private val context: Context, private val capabilities: CapabilityRegistry) {
    fun capture(activeTasks: Int = 0, failedTasks: Int = 0): SystemSnapshot {
        val ai = capabilities.aiAvailable().first.name
        val access = capabilities.snapshot().firstOrNull { it.tool.name == "READ_SCREEN" }
        val accessibility = access?.state?.name ?: "UNKNOWN"
        val network = if (NetworkMonitor.isOnline(context)) "CONNECTED" else "OFFLINE"
        val health = when { failedTasks > 0 -> "DEGRADED"; network == "OFFLINE" -> "DEGRADED"; else -> "OPTIMAL" }
        return SystemSnapshot(ai, "ONLINE", "ONLINE", "ONLINE", accessibility, "ACTIVE", "ACTIVE", network, activeTasks, failedTasks, health)
    }
}
