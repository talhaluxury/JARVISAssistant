package com.jarvis.assistant.core.config

data class JarvisConfiguration(val language: String="auto", val responseLength: String="concise", val speechSpeed: Float=1f, val hudIntensity: Float=1f, val confirmationLevel: Int=2, val developerMode: Boolean=false, val simulationMode: Boolean=false, val silentMode: Boolean=false, val focusMode: Boolean=false)
