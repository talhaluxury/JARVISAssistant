package com.jarvis.assistant.hud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HudMode { AUTO, FULL, MINIMAL, STANDBY }

enum class JarvisHudState {
    IDLE, LISTENING, THINKING, PLANNING, EXECUTING, VERIFYING, COMPLETED, ERROR, PERMISSION_REQUIRED
}

data class HudState(
    val state: JarvisHudState = JarvisHudState.IDLE,
    val mode: HudMode = HudMode.AUTO,
    val focus: String = "CORE",
    val commandStatus: String = "SYSTEM READY",
    val progress: Float = 0f,
    val eventNonce: Long = 0L
)

/**
 * In-process event/state bridge between the JARVIS app and the Live Wallpaper.
 *
 * State is also mirrored into SharedPreferences so a wallpaper service restart can recover
 * the last visual state. No hidden IPC, overlay, or permission bypass is used.
 */
object WallpaperEventBus {
    private const val PREFS = "jarvis_hud_state"
    private const val KEY_STATE = "state"
    private const val KEY_MODE = "mode"
    private const val KEY_FOCUS = "focus"
    private const val KEY_STATUS = "status"
    private const val KEY_PROGRESS = "progress"

    private lateinit var appContext: Context
    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = HudState(
            state = runCatching { JarvisHudState.valueOf(p.getString(KEY_STATE, JarvisHudState.IDLE.name)!!) }
                .getOrDefault(JarvisHudState.IDLE),
            mode = runCatching { HudMode.valueOf(p.getString(KEY_MODE, HudMode.AUTO.name)!!) }
                .getOrDefault(HudMode.AUTO),
            focus = p.getString(KEY_FOCUS, "CORE") ?: "CORE",
            commandStatus = p.getString(KEY_STATUS, "SYSTEM READY") ?: "SYSTEM READY",
            progress = p.getFloat(KEY_PROGRESS, 0f)
        )
    }

    fun emit(
        state: JarvisHudState,
        focus: String = "CORE",
        status: String = state.name,
        progress: Float = 0f
    ) {
        ensureInitialized()
        val next = _state.value.copy(
            state = state,
            focus = focus,
            commandStatus = status,
            progress = progress.coerceIn(0f, 1f),
            eventNonce = System.nanoTime()
        )
        _state.value = next
        persist(next)
    }

    fun setMode(mode: HudMode) {
        ensureInitialized()
        val next = _state.value.copy(mode = mode, eventNonce = System.nanoTime())
        _state.value = next
        persist(next)
    }

    fun setFocus(focus: String) {
        ensureInitialized()
        val next = _state.value.copy(focus = focus, eventNonce = System.nanoTime())
        _state.value = next
        persist(next)
    }

    private fun persist(value: HudState) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, value.state.name)
            .putString(KEY_MODE, value.mode.name)
            .putString(KEY_FOCUS, value.focus)
            .putString(KEY_STATUS, value.commandStatus.take(80))
            .putFloat(KEY_PROGRESS, value.progress)
            .apply()
    }

    private fun ensureInitialized() {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("WallpaperEventBus.initialize() must be called from Application.onCreate()")
        }
    }
}
