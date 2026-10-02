package com.jarvis.assistant.quotex.agent

/** Section 24. UNAVAILABLE means no reliable calendar was supplied - JARVIS never invents events. */
enum class NewsRisk { LOW, MEDIUM, HIGH, UNAVAILABLE }

enum class EventImpact { LOW, MEDIUM, HIGH }

data class EconomicEvent(val timeMs: Long, val title: String, val impact: EventImpact)

/**
 * Evaluates user- or feed-supplied scheduled events. With a null calendar the answer is UNAVAILABLE,
 * not LOW: absence of data is not absence of risk.
 */
class NewsRiskFilter(
    private val events: List<EconomicEvent>?,
    private val highWindowMs: Long = 15 * 60_000L,
    private val mediumWindowMs: Long = 60 * 60_000L
) {
    fun assess(nowMs: Long): Pair<NewsRisk, String?> {
        if (events == null) return Pair(NewsRisk.UNAVAILABLE, "No economic-calendar data available")
        var risk = NewsRisk.LOW
        var why: String? = null
        for (e in events) {
            val distance = kotlin.math.abs(e.timeMs - nowMs)
            val level = when {
                e.impact == EventImpact.HIGH && distance <= highWindowMs -> NewsRisk.HIGH
                e.impact == EventImpact.HIGH && distance <= mediumWindowMs -> NewsRisk.MEDIUM
                e.impact == EventImpact.MEDIUM && distance <= highWindowMs -> NewsRisk.MEDIUM
                else -> NewsRisk.LOW
            }
            if (level.ordinal > risk.ordinal) {
                risk = level
                why = "${e.title} (${e.impact.name.lowercase()} impact) within ${distance / 60_000L} min"
            }
        }
        return Pair(risk, why)
    }
}
