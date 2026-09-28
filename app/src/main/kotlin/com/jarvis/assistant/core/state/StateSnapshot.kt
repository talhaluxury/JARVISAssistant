package com.jarvis.assistant.core.state

/** Immutable point-in-time state used for diagnostics/recovery; contains no secrets. */
data class StateSnapshot(val id: String, val state: JarvisState, val capturedAt: Long = System.currentTimeMillis())

class StateSnapshotStore(private val maxSnapshots: Int = 20) {
    private val snapshots = ArrayDeque<StateSnapshot>()
    @Synchronized fun capture(state: JarvisState): StateSnapshot {
        val snapshot = StateSnapshot(java.util.UUID.randomUUID().toString(), state)
        snapshots.addLast(snapshot)
        while (snapshots.size > maxSnapshots) snapshots.removeFirst()
        return snapshot
    }
    @Synchronized fun all(): List<StateSnapshot> = snapshots.toList()
}
