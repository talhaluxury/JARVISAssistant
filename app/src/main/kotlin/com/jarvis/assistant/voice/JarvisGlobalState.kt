package com.jarvis.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single process-wide "what is JARVIS doing right now" flag. [com.jarvis.assistant.overlay.OverlayService]
 * updates this as it listens/thinks/speaks in the background, and
 * [com.jarvis.assistant.wallpaper.LiveWallpaperService] observes it purely to animate the orb —
 * the wallpaper never touches the microphone itself, it only reflects state that already exists
 * elsewhere in the app.
 */
object JarvisGlobalState {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    fun update(newState: VoiceState) {
        _state.value = newState
    }
}
