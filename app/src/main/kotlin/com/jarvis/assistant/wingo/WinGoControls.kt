package com.jarvis.assistant.wingo

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.wingo.capture.WinGoCaptureConsentActivity
import com.jarvis.assistant.wingo.capture.WinGoMonitorService
import com.jarvis.assistant.wingo.overlay.WinGoOverlayService

/** Start/stop entry points shared by the setup screen, the overlay and voice commands. */
class WinGoControls(private val context: Context) {

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun startMonitoring(): String {
        if (!hasOverlayPermission()) {
            return "Overlay permission is needed first. Open JARVIS, WinGo Analyzer, and allow display over other apps."
        }
        return try {
            val intent = Intent(context, WinGoCaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Approve the screen capture prompt to start game monitoring. Capture stays visible in the notification bar."
        } catch (e: Exception) {
            "Open JARVIS, WinGo Analyzer, and tap Start monitoring."
        }
    }

    fun stopMonitoring(): String {
        WinGoMonitorService.stop(context)
        WinGoOverlayService.hide(context)
        return "Game monitoring stopped."
    }
}
