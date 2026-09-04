package com.jarvis.assistant.ui.screens.settings

import android.app.Application
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.overlay.OverlayService
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.voice.JarvisVoice
import com.jarvis.assistant.wallpaper.LiveWallpaperService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "gpt-4o-mini",
    val searchApiKey: String = "",
    val language: String = "auto",
    val speechRate: Float = 1.0f,
    val wakeWordEnabled: Boolean = false,
    val voiceName: String? = null,
    val voices: List<JarvisVoice> = emptyList(),
    val aiConfigured: Boolean = false,
    val phoneControlEnabled: Boolean = false,
    val networkOnline: Boolean = false,
    val confirmEveryAction: Boolean = false,
    val backgroundJarvisEnabled: Boolean = false,
    val screenAutomationEnabled: Boolean = true,
    val overlayPermissionGranted: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as JarvisApplication).container
    private val prefs = container.securePrefs

    private val _state = MutableStateFlow(
        SettingsState(
            apiKey = prefs.aiApiKey.orEmpty(),
            baseUrl = prefs.aiBaseUrl.orEmpty(),
            model = prefs.aiModel,
            searchApiKey = prefs.searchApiKey.orEmpty(),
            language = prefs.preferredLanguage,
            speechRate = prefs.speechRate,
            wakeWordEnabled = prefs.wakeWordEnabled,
            voiceName = prefs.voiceName,
            confirmEveryAction = prefs.confirmEveryAction,
            backgroundJarvisEnabled = prefs.backgroundJarvisEnabled,
            screenAutomationEnabled = prefs.screenAutomationEnabled
        )
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The TTS engine's voice list loads asynchronously; poll briefly until it's ready
            // rather than showing an empty picker if Settings is opened right at app launch.
            repeat(10) {
                val voices = container.textToSpeechManager.listVoices()
                if (voices.isNotEmpty()) {
                    _state.value = _state.value.copy(voices = voices)
                    if (_state.value.voiceName == null) {
                        voices.firstOrNull { it.likelyMale }?.name?.let { updateVoice(it) }
                    }
                    return@launch
                }
                delay(300)
            }
        }
    }

    fun updateApiKey(value: String) {
        prefs.aiApiKey = value
        _state.value = _state.value.copy(apiKey = value)
    }

    fun updateBaseUrl(value: String) {
        prefs.aiBaseUrl = value
        _state.value = _state.value.copy(baseUrl = value)
    }

    fun updateModel(value: String) {
        prefs.aiModel = value
        _state.value = _state.value.copy(model = value)
    }

    fun updateSearchApiKey(value: String) {
        prefs.searchApiKey = value
        _state.value = _state.value.copy(searchApiKey = value)
    }

    fun updateLanguage(value: String) {
        prefs.preferredLanguage = value
        _state.value = _state.value.copy(language = value)
    }

    fun updateSpeechRate(value: Float) {
        prefs.speechRate = value
        _state.value = _state.value.copy(speechRate = value)
    }

    /** Wake word only works while the background service is running (that's where the
     * listening loop lives), so turning this on also turns on Background JARVIS. */
    fun updateWakeWord(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && !AndroidSettings.canDrawOverlays(app)) {
            requestOverlayPermission()
            return
        }
        prefs.wakeWordEnabled = enabled
        _state.value = _state.value.copy(wakeWordEnabled = enabled)
        if (enabled && !prefs.backgroundJarvisEnabled) {
            setBackgroundJarvisEnabled(true)
        }
    }

    fun updateVoice(name: String) {
        prefs.voiceName = name
        container.textToSpeechManager.setVoice(name)
        _state.value = _state.value.copy(voiceName = name)
    }

    fun testVoice() {
        viewModelScope.launch {
            container.textToSpeechManager.setRate(prefs.speechRate)
            container.textToSpeechManager.setVoice(prefs.voiceName)
            container.textToSpeechManager.speak("Systems online. JARVIS at your service.")
        }
    }

    /** Re-checks live system status — call when the Settings screen appears or resumes. */
    fun refreshStatus() {
        _state.value = _state.value.copy(
            aiConfigured = prefs.aiApiKey.isNullOrBlank().not(),
            phoneControlEnabled = JarvisAccessibilityService.isEnabled,
            networkOnline = NetworkMonitor.isOnline(getApplication()),
            overlayPermissionGranted = AndroidSettings.canDrawOverlays(getApplication())
        )
    }

    fun updateConfirmEveryAction(enabled: Boolean) {
        prefs.confirmEveryAction = enabled
        _state.value = _state.value.copy(confirmEveryAction = enabled)
    }

    /** Master kill switch for on-screen automation — tapping/typing/scrolling in other apps.
     * Plain navigation (back/home/recents) and opening apps still work when this is off. */
    fun updateScreenAutomationEnabled(enabled: Boolean) {
        prefs.screenAutomationEnabled = enabled
        _state.value = _state.value.copy(screenAutomationEnabled = enabled)
    }

    /** Opens the system screen to grant "display over other apps" — Android requires this
     * be done manually by the user; no app can grant it to itself. */
    fun requestOverlayPermission() {
        val app = getApplication<Application>()
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun setBackgroundJarvisEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && !AndroidSettings.canDrawOverlays(app)) {
            requestOverlayPermission()
            return
        }
        prefs.backgroundJarvisEnabled = enabled
        _state.value = _state.value.copy(backgroundJarvisEnabled = enabled)
        val serviceIntent = Intent(app, OverlayService::class.java)
        if (enabled) {
            ContextCompat.startForegroundService(app, serviceIntent)
        } else {
            app.stopService(serviceIntent)
        }
    }

    fun clearMemory() {
        viewModelScope.launch { container.memoryRepository.clearAll() }
    }

    /** Opens Android's own "set live wallpaper" screen with JARVIS pre-selected. The user still
     * has to tap "Set wallpaper" there themselves — no app can set the wallpaper silently. */
    fun openLiveWallpaperPicker() {
        val app = getApplication<Application>()
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(app, LiveWallpaperService::class.java)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(intent) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.conversationRepository.clearAll() }
    }
}
