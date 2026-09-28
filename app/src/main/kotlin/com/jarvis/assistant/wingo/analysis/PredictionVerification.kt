package com.jarvis.assistant.wingo.analysis

import com.jarvis.assistant.wingo.domain.BigSmall

/** The outcome of comparing a prediction (made BEFORE the round) with the number that actually came out. */
data class Verification(
    val targetPeriod: String,
    val predicted: BigSmall?,
    val actualNumber: Int,
    val actual: BigSmall,
    /** null when there was no estimate (WAIT / no lean). */
    val correct: Boolean?
) {
    val statusText: String
        get() = when (correct) {
            true -> "✓ CORRECT"
            false -> "✕ WRONG"
            null -> "NO ESTIMATE"
        }

    fun toText(): String =
        "PREDICTION: ${predicted?.name ?: "WAIT"}\nACTUAL: ${actual.name} ($actualNumber)\nSTATUS: $statusText"
}

object PredictionVerifier {
    /** Big/Small is always derived from the number (0-4 SMALL, 5-9 BIG), never read from anywhere else. */
    fun verify(targetPeriod: String, predicted: BigSmall?, actualNumber: Int): Verification {
        val actual = BigSmall.fromNumber(actualNumber)
        return Verification(targetPeriod, predicted, actualNumber, actual, predicted?.let { it == actual })
    }
}
