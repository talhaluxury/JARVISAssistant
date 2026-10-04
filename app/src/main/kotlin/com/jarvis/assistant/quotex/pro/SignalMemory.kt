package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.trading.QuotexDecision

enum class MemoryOutcome { WIN, LOSS, DRAW }
enum class MemoryAction { OK, REDUCE, WAIT }

data class MemoryVerdict(
    val action: MemoryAction,
    val scoreMultiplier: Double,
    val duplicate: Boolean,
    val reasons: List<String>
)

/**
 * Remembers recent setups so the engine does not repeat a setup that keeps failing in the same regime,
 * re-announce the same setup, or flip CALL/PUT every few candles. In-memory only; the journal is the durable copy.
 */
class SignalMemory(
    private val capacity: Int = 60,
    private val duplicateWindowMs: Long = 60_000L,
    private val minSampleForFailure: Int = 3
) {
    private class Rec(val key: String, val regime: String, val direction: QuotexDecision, val timeMs: Long, var outcome: MemoryOutcome? = null)

    private val records = ArrayDeque<Rec>()

    @Synchronized
    fun record(key: String, regime: String, direction: QuotexDecision, timeMs: Long) {
        records.addLast(Rec(key, regime, direction, timeMs))
        while (records.size > capacity) records.removeFirst()
    }

    /** Marks the newest unresolved record with this key. */
    @Synchronized
    fun resolve(key: String, outcome: MemoryOutcome) {
        records.lastOrNull { it.key == key && it.outcome == null }?.outcome = outcome
    }

    @Synchronized
    fun verdict(key: String, regime: String, direction: QuotexDecision, nowMs: Long): MemoryVerdict {
        val reasons = ArrayList<String>()
        var action = MemoryAction.OK
        var mult = 1.0

        val dup = records.any { it.key == key && nowMs - it.timeMs in 0..duplicateWindowMs }
        if (dup) reasons += "same setup already reported in the last ${duplicateWindowMs / 1000}s"

        // Rapid alternation: look at the last 6 directional records.
        val dirs = records.filter { it.direction == QuotexDecision.CALL || it.direction == QuotexDecision.PUT }
            .takeLast(6).map { it.direction }
        var flips = 0
        for (i in 1 until dirs.size) if (dirs[i] != dirs[i - 1]) flips++
        if (flips >= 3) {
            action = MemoryAction.WAIT
            reasons += "unstable market: direction flipped $flips times in the last ${dirs.size} signals"
        }

        // Repeated failures of this setup under the same regime.
        val resolved = records.filter { it.key == key && it.regime == regime && it.outcome != null && it.outcome != MemoryOutcome.DRAW }
            .takeLast(8)
        if (resolved.size >= minSampleForFailure) {
            val losses = resolved.count { it.outcome == MemoryOutcome.LOSS }
            val rate = losses.toDouble() / resolved.size
            if (rate >= 0.67) {
                action = MemoryAction.WAIT
                reasons += "this setup failed $losses of the last ${resolved.size} times in the $regime regime"
            } else if (rate >= 0.5 && action != MemoryAction.WAIT) {
                action = MemoryAction.REDUCE
                mult = 0.8
                reasons += "this setup failed $losses of the last ${resolved.size} times in the $regime regime"
            }
        }
        return MemoryVerdict(action, mult, dup, reasons)
    }
}
