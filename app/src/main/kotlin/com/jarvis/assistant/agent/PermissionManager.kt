package com.jarvis.assistant.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.JarvisAccessibilityService

enum class CapabilityState { READY, REQUIRED, OPTIONAL, DENIED }
data class CapabilityStatus(val name: String, val state: CapabilityState, val explanation: String)

class PermissionManager(private val context: Context) {
    fun snapshot(): List<CapabilityStatus> = buildList {
        add(CapabilityStatus("Microphone", if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) CapabilityState.READY else CapabilityState.REQUIRED, "Required for voice input."))
        if (Build.VERSION.SDK_INT >= 33) add(CapabilityStatus("Notifications", if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) CapabilityState.READY else CapabilityState.OPTIONAL, "Used for foreground-service status."))
        add(CapabilityStatus("Phone control / Accessibility", if (JarvisAccessibilityService.isEnabled) CapabilityState.READY else CapabilityState.REQUIRED, "Required to inspect and interact with supported foreground-app UI."))
    }
}
