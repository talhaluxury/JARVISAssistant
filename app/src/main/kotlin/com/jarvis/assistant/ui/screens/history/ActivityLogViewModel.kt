package com.jarvis.assistant.ui.screens.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.data.local.db.entity.CommandHistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * #15 ACTIVITY LOG — a real, timestamped feed of what JARVIS actually did (command received,
 * action executed, result, status), as distinct from the conversation list. Backed by the same
 * [com.jarvis.assistant.data.repository.BrainRepository] the Dashboard and diagnostics use —
 * no separate/fake logging path.
 */
class ActivityLogViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as JarvisApplication).container

    private val _entries = MutableStateFlow<List<CommandHistoryEntity>>(emptyList())
    val entries: StateFlow<List<CommandHistoryEntity>> = _entries.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _entries.value = container.brainRepository.recentCommands(60) }
    }

    fun clear() {
        viewModelScope.launch {
            container.brainRepository.clearCommandHistory()
            refresh()
        }
    }
}
