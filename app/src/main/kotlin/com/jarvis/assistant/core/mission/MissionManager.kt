package com.jarvis.assistant.core.mission

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** In-process mission coordinator with cancellation and priority ordering. */
class MissionManager {
    private val mutex = Mutex()
    private val missions = LinkedHashMap<String, Mission>()
    private var stopped = false

    suspend fun create(goal: String, priority: Int = 50): Mission = mutex.withLock {
        val mission = Mission(UUID.randomUUID().toString(), goal, priority, MissionStatus.QUEUED)
        missions[mission.id] = mission
        mission
    }
    suspend fun update(id: String, transform: (Mission) -> Mission): Mission? = mutex.withLock {
        missions[id]?.let { transform(it).copy(updatedAt = System.currentTimeMillis()).also { next -> missions[id] = next } }
    }
    suspend fun all(): List<Mission> = mutex.withLock { missions.values.sortedByDescending { it.priority } }
    fun emergencyStop() { stopped = true }
    fun clearEmergencyStop() { stopped = false }
    fun isEmergencyStopped(): Boolean = stopped
}
