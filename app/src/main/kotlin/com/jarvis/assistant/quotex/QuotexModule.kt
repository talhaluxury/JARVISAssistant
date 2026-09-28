package com.jarvis.assistant.quotex

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.quotex.capture.QuotexCaptureConsentActivity
import com.jarvis.assistant.quotex.capture.QuotexMonitorService
import com.jarvis.assistant.quotex.data.QuotexCandleRepository
import com.jarvis.assistant.quotex.data.QuotexDatabase
import com.jarvis.assistant.quotex.overlay.QuotexOverlayService
import com.jarvis.assistant.quotex.voice.QuotexChatController
import com.jarvis.assistant.quotex.voice.QuotexVoiceController

/** Start/stop entry points shared by the screen, the overlay and voice commands. */
class QuotexControls(private val context: Context) {
    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun startMonitoring(): String {
        if (!hasOverlayPermission()) {
            return "Overlay permission is needed first. Open JARVIS, Quotex Analyzer, and allow display over other apps."
        }
        return try {
            context.startActivity(Intent(context, QuotexCaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Approve the screen capture prompt to start Quotex monitoring. Capture stays visible in the notification bar."
        } catch (e: Exception) {
            "Open JARVIS, Quotex Analyzer, and tap Start monitoring."
        }
    }

    fun stopMonitoring(): String {
        QuotexMonitorService.stop(context)
        QuotexOverlayService.hide(context)
        return "Quotex monitoring stopped."
    }
}

/** Single entry point wired into AppContainer (`container.quotex`). Everything is created lazily. */
class QuotexModule(context: Context) {
    private val appContext = context.applicationContext
    private val database: QuotexDatabase by lazy { QuotexDatabase.create(appContext) }

    val settings: QuotexSettings by lazy { QuotexSettings(appContext) }
    val repository: QuotexCandleRepository by lazy { QuotexCandleRepository(database.candleDao()) }
    val coordinator: QuotexCoordinator by lazy { QuotexCoordinator(repository, settings) }
    val chat: QuotexChatController by lazy { QuotexChatController(coordinator) }
    val controls: QuotexControls by lazy { QuotexControls(appContext) }
    val voice: QuotexVoiceController by lazy { QuotexVoiceController({ coordinator }, { chat }, controls) }
}
