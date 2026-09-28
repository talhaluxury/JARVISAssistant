package com.jarvis.assistant.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.notifications.JarvisNotificationListenerService

enum class CapabilityState { READY, REQUIRED, OPTIONAL, DENIED }
data class CapabilityStatus(val name: String, val state: CapabilityState, val explanation: String)

class PermissionManager(private val context: Context) {
    fun snapshot(): List<CapabilityStatus> = buildList {
        add(CapabilityStatus("Microphone", if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) CapabilityState.READY else CapabilityState.REQUIRED, "Required for voice input."))
        if (Build.VERSION.SDK_INT >= 33) add(CapabilityStatus("Notifications", if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) CapabilityState.READY else CapabilityState.OPTIONAL, "Used for foreground-service status."))
        add(CapabilityStatus("Phone control / Accessibility", if (JarvisAccessibilityService.isEnabled) CapabilityState.READY else CapabilityState.REQUIRED, "Required to inspect and interact with supported foreground-app UI."))
        add(CapabilityStatus("Notification access", if (JarvisNotificationListenerService.isEnabled) CapabilityState.READY else CapabilityState.OPTIONAL, "Lets JARVIS summarize notifications only after you enable Android Notification Access."))
        if (Build.VERSION.SDK_INT >= 31) {
            val bluetooth = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            add(CapabilityStatus("Bluetooth", if (bluetooth) CapabilityState.READY else CapabilityState.OPTIONAL, "Used only to report Bluetooth state on Android 12+."))
        }
    }
}
