package com.jarvis.assistant.core.state

import com.jarvis.assistant.core.event.JarvisEvent
import com.jarvis.assistant.core.event.JarvisEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle-safe state machine. Every transition is observable and retained briefly for context. */
class JarvisStateStore(private val events: JarvisEventBus, private val historyLimit: Int = 32) {
    private val _state = MutableStateFlow(JarvisState())
    val state: StateFlow<JarvisState> = _state.asStateFlow()
    private val _history = ArrayDeque<JarvisState>()
    val history: List<JarvisState> @Synchronized get() = _history.toList()

    @Synchronized fun update(transform: (JarvisState) -> JarvisState) {
        val previous = _state.value
        val next = transform(previous).copy(updatedAt = System.currentTimeMillis())
        if (next.mode != previous.mode || next.taskId != previous.taskId) {
            _history.addLast(previous)
            while (_history.size > historyLimit) _history.removeFirst()
        }
        _state.value = next
        events.emit(JarvisEvent.StateChanged(next.mode.name))
    }

    fun transition(mode: JarvisMode) = update { it.copy(mode = mode) }
    fun emergencyStop() = update { it.copy(mode = JarvisMode.EMERGENCY_STOP, emergencyStop = true, safeMode = true) }
    fun clearEmergencyStop() = update { it.copy(mode = JarvisMode.STANDBY, emergencyStop = false) }
    fun enterSafeMode() = update { it.copy(mode = JarvisMode.SAFE_MODE, safeMode = true) }
}
