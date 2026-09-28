package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.wingo.analysis.RollingHits

/**
 * Online walk-forward record of one strategy's own directional calls (independent of whether the
 * confluence engine ever surfaced a setup around it - section 19's "every strategy independently
 * testable"). Only past, already-known outcomes ever reach it, so it cannot leak future information.
 * The resulting weight feeds back into [ConfluenceEngine] (section 21's calibration): a strategy whose
 * own record turns poor automatically loses influence rather than being trusted forever.
 */
class StrategyPerformanceTracker(window: Int = 200, private val minSamples: Int = 20) {
    private val rolling = RollingHits(window)

    fun record(hit: Boolean) = rolling.record(hit)

    val samples: Int get() = rolling.samples
    val hits: Int get() = rolling.hits
    val accuracy: Double? get() = rolling.accuracy

    fun weight(): Double {
        if (rolling.samples < minSamples) return 1.0
        val z = rolling.zScore() ?: return 1.0
        return (1.0 + 0.25 * z).coerceIn(0.1, 2.0)
    }
}

/** Read-only snapshot used by the UI/chat, mirroring WinGo's ModelStatus. */
data class StrategyStatus(val name: String, val samples: Int, val accuracy: Double?, val weight: Double)
