package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Lightweight amplitude-based "is someone talking right now" detector, used only for
 * barge-in — noticing the user has started speaking while JARVIS is still talking, so it
 * can stop and listen. This is a volume trigger, not speech recognition: it never transcribes
 * or stores anything, it just watches microphone loudness and fires a callback once.
 */
class VoiceActivityDetector(private val context: Context) {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * [amplitudeThreshold] is a tunable RMS cutoff and [requiredConsecutiveHits] how many
     * loud reads in a row are needed before firing. Raised from the original defaults because
     * on many phones (especially without earphones) the device's own speaker output leaks
     * into the mic loudly enough to falsely trigger this mid-sentence, cutting JARVIS off.
     * [AcousticEchoCanceler] (below) is the real fix for that when the device supports it;
     * these thresholds are the safety margin for devices where it isn't available.
     */
    fun start(amplitudeThreshold: Double = 4200.0, requiredConsecutiveHits: Int = 6, onSpeechDetected: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        stop()
        job = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@launch
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (e: SecurityException) {
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@launch
            }

            // The actual fix: cancel the device's own speaker output (JARVIS's own voice)
            // out of what the mic picks up, instead of just picking a high volume cutoff.
            var echoCanceler: AcousticEchoCanceler? = null
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                } catch (_: Exception) {}
            }

            val buffer = ShortArray(minBuf)
            var consecutiveHits = 0
            try {
                record.startRecording()
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) sum += (buffer[i] * buffer[i]).toDouble()
                        val rms = sqrt(sum / read)
                        consecutiveHits = if (rms > amplitudeThreshold) consecutiveHits + 1 else 0
                        if (consecutiveHits >= requiredConsecutiveHits) {
                            onSpeechDetected()
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // The detector simply stops if the mic can't be read (e.g. another app grabbed it).
            } finally {
                try {
                    record.stop()
                } catch (e: Exception) { }
                try { echoCanceler?.release() } catch (_: Exception) {}
                record.release()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
