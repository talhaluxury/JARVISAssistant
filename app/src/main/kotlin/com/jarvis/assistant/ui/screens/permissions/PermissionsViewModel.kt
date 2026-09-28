package com.jarvis.assistant.ui.screens.permissions

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.agent.CapabilityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * #12 PERMISSION INTELLIGENCE — surfaces the existing [com.jarvis.assistant.agent.PermissionManager]
 * (previously built but never wired to a screen) as a real "JARVIS Permission Center". Never
 * grants or bypasses anything itself — every action here just routes to the correct Android
 * settings screen for the human to grant explicitly, per Android's own security model.
 */
class PermissionsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as JarvisApplication).container

    private val _permissions = MutableStateFlow<List<CapabilityStatus>>(emptyList())
    val permissions: StateFlow<List<CapabilityStatus>> = _permissions.asStateFlow()

    init { refresh() }

    fun refresh() {
        _permissions.value = container.permissionManager.snapshot()
    }

    /** Routes each permission row to the correct Android settings screen. JARVIS cannot flip
     * any of these switches itself — Android requires the human to do it, on purpose. */
    fun openSettingsFor(name: String) {
        val app = getApplication<Application>()
        val intent = when (name) {
            "Phone control / Accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "Notification access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
            }
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { app.startActivity(intent) }
    }
}
