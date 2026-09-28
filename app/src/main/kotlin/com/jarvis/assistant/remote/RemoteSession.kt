package com.jarvis.assistant.remote

/** Shared state for the visible, permission-based Internet remote session. */
object RemoteSession {
    @Volatile var active: Boolean = false
        private set

    fun start() { active = true }
    fun stop() {
        active = false
        RemoteRelayClient.stop()
    }
}
