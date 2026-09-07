package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A voice available on this device's TTS engine, described in plain terms for the Settings UI. */
data class JarvisVoice(
    val name: String,
    val displayLabel: String,
    val likelyMale: Boolean
)

class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingVoiceName: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                // Default to the best male-sounding voice exposed by the installed TTS engine.
                // A user-selected voice still wins when one has been explicitly saved.
                val requested = pendingVoiceName
                if (requested != null) {
                    applyVoice(requested)
                } else {
                    autoSelectMaleVoice()?.let(::applyVoice)
                }
            }
        }
    }

    fun setLanguage(tag: String?) {
        val locale = tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        tts?.language = locale
    }

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Lists the voices this device's TTS engine offers, male-sounding ones first. Android's TTS
     * Voice class has no explicit gender field, so this relies on the naming convention most
     * engines (including Google's) follow — voices with "male"/"#male" in their internal name —
     * and simply falls back to whatever is available if the engine doesn't expose that.
     */
    fun listVoices(): List<JarvisVoice> {
        val engine = tts ?: return emptyList()
        val all = engine.voices ?: return emptyList()
        val candidates = all.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
            .ifEmpty { all.filter { it.locale.language == "en" } }
            .ifEmpty { all.toList() }
        return candidates
            .distinctBy { it.name }
            .map { voice -> JarvisVoice(voice.name, prettyLabel(voice), looksMale(voice)) }
            .sortedByDescending { it.likelyMale }
    }

    /** Best-effort pick of a male-sounding voice, or null if the engine offers none. */
    fun autoSelectMaleVoice(): String? = listVoices().firstOrNull { it.likelyMale }?.name

    fun setVoice(name: String?) {
        if (!ready) {
            pendingVoiceName = name
            return
        }
        applyVoice(name)
    }

    private fun applyVoice(name: String?) {
        val engine = tts ?: return
        val match = name?.let { target -> engine.voices?.firstOrNull { it.name == target } }
        if (match != null) engine.voice = match
    }

    private fun looksMale(voice: Voice): Boolean {
        val n = voice.name.lowercase()
        return when {
            n.contains("female") -> false
            n.contains("male") -> true
            else -> false
        }
    }

    private fun prettyLabel(voice: Voice): String {
        val gender = when {
            looksMale(voice) -> "Male"
            voice.name.lowercase().contains("female") -> "Female"
            else -> "Voice"
        }
        val quality = if (voice.quality >= Voice.QUALITY_HIGH) "HD" else null
        return listOfNotNull(gender, voice.locale.displayLanguage, quality).joinToString(" \u00b7 ")
    }

    /** Suspends until the given text has finished being spoken (or fails). */
    suspend fun speak(text: String): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return true
        val utteranceId = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) cont.resume(false)
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
