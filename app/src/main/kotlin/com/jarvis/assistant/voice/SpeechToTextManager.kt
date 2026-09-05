package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed class SpeechEvent {
    data class PartialResult(val text: String) : SpeechEvent()
    data class FinalResult(val text: String) : SpeechEvent()
    data class Error(val message: String) : SpeechEvent()
    object ListeningStarted : SpeechEvent()
    object ListeningEnded : SpeechEvent()
}

/**
 * Foreground-only microphone use: listening starts when the user taps the
 * mic button and stops as soon as a result/error/silence comes back. There
 * is no hidden or background recording — the mic indicator in the UI is
 * driven directly by ListeningStarted/ListeningEnded from this class.
 */
class SpeechToTextManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** languageTag e.g. "en-US", "ur-PK", or null to use the device default. */
    fun listen(languageTag: String?): Flow<SpeechEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(SpeechEvent.Error("Speech recognition isn't available on this device."))
            close()
            return@callbackFlow
        }

        // A single recognizer instance is allowed at a time. Cancel any stale session before
        // creating the next one; this prevents ERROR_RECOGNIZER_BUSY and leaked callbacks when
        // wake-word, barge-in, or manual listening transitions happen close together.
        recognizer?.runCatching { stopListening(); cancel(); destroy() }
        recognizer = null
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.ListeningStarted)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                trySend(SpeechEvent.ListeningEnded)
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(errorMessage(error)))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                trySend(SpeechEvent.FinalResult(text))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) trySend(SpeechEvent.PartialResult(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        sr.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Give the user real pauses to think mid-sentence instead of cutting them off —
            // most devices default to ~1s of silence, which is too eager for natural speech.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            if (!languageTag.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        sr.startListening(intent)

        awaitClose {
            sr.stopListening()
            sr.destroy()
            recognizer = null
        }
    }

    @Synchronized
    fun cancel() {
        recognizer?.runCatching { stopListening(); cancel(); destroy() }
        recognizer = null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy, try again."
        else -> "Speech recognition error."
    }
}
