package com.jarvis.assistant.core.verification

import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand

data class VerificationResult(val passed: Boolean, val confidence: Float, val detail: String)

class VerificationEngine {
    fun verify(command: JarvisCommand, result: ExecutionResult): VerificationResult = when (result) {
        is ExecutionResult.Success -> VerificationResult(true, 0.85f, "Executor reported success for ${command::class.simpleName}")
        is ExecutionResult.Failure -> VerificationResult(false, 0.95f, result.message)
    }
}
