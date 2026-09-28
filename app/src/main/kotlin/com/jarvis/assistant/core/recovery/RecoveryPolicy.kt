package com.jarvis.assistant.core.recovery

import com.jarvis.assistant.agent.FailureReason

enum class RecoveryStrategy { RETRY, RECHECK_PERMISSION, REFRESH_CONTEXT, ABORT, ASK_USER }

class RecoveryPolicy {
    fun strategy(reason: FailureReason, attempt: Int): RecoveryStrategy = when {
        attempt >= 2 -> RecoveryStrategy.ASK_USER
        reason == FailureReason.PERMISSION_MISSING -> RecoveryStrategy.RECHECK_PERMISSION
        reason == FailureReason.ELEMENT_NOT_FOUND -> RecoveryStrategy.REFRESH_CONTEXT
        reason == FailureReason.NETWORK_UNAVAILABLE -> RecoveryStrategy.ABORT
        else -> RecoveryStrategy.RETRY
    }
}
