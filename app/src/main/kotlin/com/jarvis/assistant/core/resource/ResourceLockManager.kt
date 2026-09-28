package com.jarvis.assistant.core.resource

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Per-resource async locks prevent two missions from controlling the same UI/app concurrently. */
class ResourceLockManager {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withLock(resource: String, block: suspend () -> T): T = locks.getOrPut(resource) { Mutex() }.withLock { block() }
}
