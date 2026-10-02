package com.jarvis.assistant.quotex.analysis

/**
 * Sections 5 and 8: structural events that need CONFIRMATION, never a single wick.
 *
 * - Break of Structure (BOS): price CLOSES beyond the last swing in the direction of the prevailing trend
 *   (HH/HL up-trend breaking its last high, LH/LL down-trend breaking its last low) and is still beyond it now.
 * - Change of Character (CHoCH): the first confirmed CLOSE against the prevailing trend (up-trend closing
 *   below its last higher low, down-trend closing above its last lower high).
 * - False breakout: a swing level is pierced, but price closes back inside it.
 * - Liquidity: equal highs / equal lows (several swings at nearly the same price) and a sweep of them that
 *   closes back inside.
 *
 * These describe the market. They never generate a trade by themselves; the analyzer only uses them to
 * veto (WAIT) a setup that points against a fresh structural event.
 */
enum class StructureEventType(val label: String, val bias: Int) {
    BREAK_OF_STRUCTURE_UP("Break of structure up", 1),
    BREAK_OF_STRUCTURE_DOWN("Break of structure down", -1),
    CHANGE_OF_CHARACTER_UP("Change of character up", 1),
    CHANGE_OF_CHARACTER_DOWN("Change of character down", -1),
    FALSE_BREAKOUT_UP("False breakout above resistance", -1),
    FALSE_BREAKOUT_DOWN("False breakout below support", 1),
    LIQUIDITY_SWEEP_UP("Sweep of equal highs", -1),
    LIQUIDITY_SWEEP_DOWN("Sweep of equal lows", 1)
}

data class StructureEvent(val type: StructureEventType, val level: Double, val candlesAgo: Int)

/** Equal highs ([isHigh]) or equal lows: several swings within a small ATR fraction of each other. */
data class LiquidityZone(val price: Double, val isHigh: Boolean, val touches: Int)

data class StructureReport(val events: List<StructureEvent>, val zones: List<LiquidityZone>) {
    companion object {
        val EMPTY = StructureReport(emptyList(), emptyList())
    }
}

object StructureEvents {

    private class Cluster(var price: Double, var touches: Int, var lastIndex: Int)

    /**
     * @param recent how many of the newest candles a break may have happened in to still count as an event
     * @param confirmAtr a close must be beyond the level by this fraction of ATR (filters tiny, noisy breaks)
     * @param equalAtr swings closer than this fraction of ATR count as the same level
     */
    fun analyze(
        series: PriceSeries,
        swings: List<Swing> = MarketStructure.swings(series),
        recent: Int = 5,
        confirmAtr: Double = 0.1,
        equalAtr: Double = 0.25
    ): StructureReport {
        val n = series.size
        if (n < 20) return StructureReport.EMPTY
        val last = n - 1
        val atr = series.atr14[last]
        if (atr.isNaN() || atr <= 0.0) return StructureReport.EMPTY
        val buffer = atr * confirmAtr
        val closes = series.closes
        val highs = series.highs
        val lows = series.lows
        val windowStart = maxOf(0, n - recent)

        val swingHighs = swings.filter { it.isHigh }
        val swingLows = swings.filter { !it.isHigh }
        val events = ArrayList<StructureEvent>()

        if (swingHighs.size >= 2 && swingLows.size >= 2) {
            val h1 = swingHighs[swingHighs.size - 2]
            val h2 = swingHighs[swingHighs.size - 1]
            val l1 = swingLows[swingLows.size - 2]
            val l2 = swingLows[swingLows.size - 1]
            val upTrend = h2.price > h1.price && l2.price > l1.price
            val downTrend = h2.price < h1.price && l2.price < l1.price

            // Close above the last swing high that is still above it now.
            val breakUp = (maxOf(windowStart, h2.index + 1)..last).lastOrNull { closes[it] > h2.price + buffer }
            if (breakUp != null && closes[last] > h2.price) {
                when {
                    upTrend -> events.add(StructureEvent(StructureEventType.BREAK_OF_STRUCTURE_UP, h2.price, last - breakUp))
                    downTrend -> events.add(StructureEvent(StructureEventType.CHANGE_OF_CHARACTER_UP, h2.price, last - breakUp))
                }
            }
            // Close below the last swing low that is still below it now.
            val breakDown = (maxOf(windowStart, l2.index + 1)..last).lastOrNull { closes[it] < l2.price - buffer }
            if (breakDown != null && closes[last] < l2.price) {
                when {
                    downTrend -> events.add(StructureEvent(StructureEventType.BREAK_OF_STRUCTURE_DOWN, l2.price, last - breakDown))
                    upTrend -> events.add(StructureEvent(StructureEventType.CHANGE_OF_CHARACTER_DOWN, l2.price, last - breakDown))
                }
            }
        }

        // False breakouts: the newest swing level was pierced, but price is back inside it now.
        val falseWindow = maxOf(0, n - 3)
        swingHighs.lastOrNull()?.let { h ->
            val pierced = (maxOf(falseWindow, h.index + 1)..last).lastOrNull { highs[it] > h.price }
            if (pierced != null && closes[last] < h.price - buffer) {
                events.add(StructureEvent(StructureEventType.FALSE_BREAKOUT_UP, h.price, last - pierced))
            }
        }
        swingLows.lastOrNull()?.let { l ->
            val pierced = (maxOf(falseWindow, l.index + 1)..last).lastOrNull { lows[it] < l.price }
            if (pierced != null && closes[last] > l.price + buffer) {
                events.add(StructureEvent(StructureEventType.FALSE_BREAKOUT_DOWN, l.price, last - pierced))
            }
        }

        // Liquidity: equal highs / equal lows from the last ~120 candles.
        val zones = ArrayList<LiquidityZone>()
        for (isHigh in listOf(true, false)) {
            val pts = swings.filter { it.isHigh == isHigh && it.index >= n - 120 }.sortedBy { it.price }
            val clusters = ArrayList<Cluster>()
            for (s in pts) {
                val c = clusters.lastOrNull()
                if (c != null && s.price - c.price <= atr * equalAtr) {
                    c.price = (c.price * c.touches + s.price) / (c.touches + 1)
                    c.touches++
                    c.lastIndex = maxOf(c.lastIndex, s.index)
                } else {
                    clusters.add(Cluster(s.price, 1, s.index))
                }
            }
            for (c in clusters.filter { it.touches >= 2 }) {
                zones.add(LiquidityZone(c.price, isHigh, c.touches))
                // Sweep: pierced after the last touch, but the close is back inside the zone.
                if (isHigh) {
                    val k = (maxOf(windowStart, c.lastIndex + 1)..last).lastOrNull { highs[it] > c.price }
                    if (k != null && closes[last] < c.price - buffer) {
                        events.add(StructureEvent(StructureEventType.LIQUIDITY_SWEEP_UP, c.price, last - k))
                    }
                } else {
                    val k = (maxOf(windowStart, c.lastIndex + 1)..last).lastOrNull { lows[it] < c.price }
                    if (k != null && closes[last] > c.price + buffer) {
                        events.add(StructureEvent(StructureEventType.LIQUIDITY_SWEEP_DOWN, c.price, last - k))
                    }
                }
            }
        }
        return StructureReport(events.distinctBy { it.type }, zones)
    }

    /** Events whose bias points against [dir] (CALL against = bearish events, PUT against = bullish events). */
    fun against(report: StructureReport, callSide: Boolean, maxAgo: Int = 3): List<StructureEvent> =
        report.events.filter { it.candlesAgo <= maxAgo && (if (callSide) it.type.bias < 0 else it.type.bias > 0) }
}
