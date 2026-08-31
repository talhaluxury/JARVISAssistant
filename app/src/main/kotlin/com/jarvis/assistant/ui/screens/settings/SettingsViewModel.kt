package com.jarvis.assistant.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.voice.JarvisVoice
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
    val networkOnline: Boolean = false
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
            voiceName = prefs.voiceName
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

    fun updateWakeWord(enabled: Boolean) {
        prefs.wakeWordEnabled = enabled
        _state.value = _state.value.copy(wakeWordEnabled = enabled)
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
            networkOnline = NetworkMonitor.isOnline(getApplication())
        )
    }

    fun clearMemory() {
        viewModelScope.launch { container.memoryRepository.clearAll() }
    }

    fun clearHistory() {
        viewModelScope.launch { container.conversationRepository.clearAll() }
    }
}
