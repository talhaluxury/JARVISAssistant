package com.jarvis.assistant.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
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
    val wakeWordEnabled: Boolean = false
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
            wakeWordEnabled = prefs.wakeWordEnabled
        )
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

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

    fun clearMemory() {
        viewModelScope.launch { container.memoryRepository.clearAll() }
    }

    fun clearHistory() {
        viewModelScope.launch { container.conversationRepository.clearAll() }
    }
}
