package com.jarvis.assistant.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/** Lifecycle-safe internal event bus. Events are structured and never used as executable code. */
class JarvisEventBus {
    private val _events = MutableSharedFlow<JarvisEvent>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<JarvisEvent> = _events.asSharedFlow()

    fun emit(event: JarvisEvent) { _events.tryEmit(event) }
}

sealed class JarvisEvent(open val timestamp: Long = System.currentTimeMillis()) {
    data class CommandReceived(val text: String) : JarvisEvent()
    data class PlanCreated(val taskId: String, val actions: Int) : JarvisEvent()
    data class ActionStarted(val taskId: String, val action: String) : JarvisEvent()
    data class ActionFinished(val taskId: String, val action: String, val success: Boolean, val detail: String) : JarvisEvent()
    data class Verification(val taskId: String, val success: Boolean, val detail: String) : JarvisEvent()
    data class Recovery(val taskId: String, val strategy: String, val detail: String) : JarvisEvent()
    data class PermissionRequired(val capability: String, val detail: String) : JarvisEvent()
    data class Error(val component: String, val detail: String) : JarvisEvent()
    data class StateChanged(val state: String) : JarvisEvent()
    data class ProactiveSuggestion(val text: String) : JarvisEvent()
}
