package com.jarvis.assistant.ui.screens.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.data.local.db.dao.CommandUsageCount
import com.jarvis.assistant.data.local.db.entity.SystemEventEntity
import com.jarvis.assistant.data.local.db.entity.TaskOutcomeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = true,
    val totalTasks: Int = 0,
    val successRate: Float = 0f,
    val averageExecutionTimeMs: Long = 0L,
    val recentOutcomes: List<TaskOutcomeEntity> = emptyList(), // oldest first, for the timeline strip
    val mostCommonFailures: List<Pair<String, Int>> = emptyList(),
    val flaggedWorkflows: List<String> = emptyList(),
    val mostUsedCommands: List<CommandUsageCount> = emptyList(),
    val recentEvents: List<SystemEventEntity> = emptyList()
)

/**
 * #56 CONTINUOUS IMPROVEMENT DASHBOARD — every field here is read straight from
 * BrainRepository/LearningEngine; nothing on this screen is a placeholder or sample value.
 * Command contents are never shown here (only command TYPE and counts), per the spec's
 * "do not expose private command contents unnecessarily".
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as JarvisApplication).container

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val stats = container.learningEngine.stats()
            val outcomes = container.brainRepository.recentOutcomes(30).reversed()
            val usage = container.brainRepository.commandUsageCounts(8)
            val events = container.brainRepository.recentEvents(15)
            _state.value = DashboardUiState(
                loading = false,
                totalTasks = stats.totalTasks,
                successRate = stats.successRate,
                averageExecutionTimeMs = stats.averageExecutionTimeMs,
                recentOutcomes = outcomes,
                mostCommonFailures = stats.mostCommonFailures,
                flaggedWorkflows = stats.flaggedWorkflows,
                mostUsedCommands = usage,
                recentEvents = events
            )
        }
    }

    fun clearStats() {
        viewModelScope.launch {
            container.brainRepository.clearTaskOutcomes()
            container.brainRepository.clearCommandHistory()
            container.brainRepository.clearEvents()
            refresh()
        }
    }
}
