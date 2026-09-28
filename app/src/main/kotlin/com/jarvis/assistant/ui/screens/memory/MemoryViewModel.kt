package com.jarvis.assistant.ui.screens.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.data.local.db.entity.MemoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as JarvisApplication).container.memoryRepository

    val memories: StateFlow<List<MemoryEntity>> = repository.observeMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMemory(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch { repository.remember(content) }
    }

    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch { repository.forget(memory) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
