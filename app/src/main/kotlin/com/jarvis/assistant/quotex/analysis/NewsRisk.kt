package com.jarvis.assistant.quotex.analysis

/** Section 24. UNKNOWN means "no reliable calendar available" - never a made-up LOW. */
enum class NewsRisk { LOW, MEDIUM, HIGH, UNKNOWN }

/** [impact] 1 = low, 2 = medium, 3 = high. */
data class EconomicEvent(val timeMs: Long, val currencies: Set<String>, val impact: Int, val title: String)

interface EconomicCalendarSource {
    /** Events between the two times, or null when the source is unavailable (so we never guess). */
    fun events(fromMs: Long, toMs: Long): List<EconomicEvent>?
}

data class NewsRiskAssessment(val risk: NewsRisk, val reason: String)

class NewsRiskFilter(
    private val source: EconomicCalendarSource? = null,
    private val highWindowMs: Long = 15 * 60_000L,
    private val mediumWindowMs: Long = 60 * 60_000L
) {
    fun assess(asset: String?, nowMs: Long): NewsRiskAssessment {
        val src = source ?: return NewsRiskAssessment(NewsRisk.UNKNOWN, "No economic-calendar source is connected, so event risk is unknown.")
        val events = src.events(nowMs - 5 * 60_000L, nowMs + mediumWindowMs)
            ?: return NewsRiskAssessment(NewsRisk.UNKNOWN, "The economic calendar is unavailable right now.")
        val currencies = currenciesOf(asset)
        val relevant = events.filter { currencies.isEmpty() || it.currencies.any { c -> c in currencies } }
        val high = relevant.firstOrNull { it.impact >= 3 && it.timeMs <= nowMs + highWindowMs }
        if (high != null) return NewsRiskAssessment(NewsRisk.HIGH, "HIGH EVENT RISK: ${high.title} is imminent or just released.")
        val medium = relevant.firstOrNull { it.impact >= 2 }
        if (medium != null) return NewsRiskAssessment(NewsRisk.MEDIUM, "Event approaching within the hour: ${medium.title}.")
        return NewsRiskAssessment(NewsRisk.LOW, "No significant scheduled events in the next hour.")
    }

    private fun currenciesOf(asset: String?): Set<String> {
        if (asset == null) return emptySet()
        return Regex("[A-Z]{3}").findAll(asset.uppercase()).map { it.value }.filter { it != "OTC" }.toSet()
    }
}
