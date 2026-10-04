package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.quotex.analysis.PriceSeries
import com.jarvis.assistant.quotex.domain.Candle

/** Editable description of a Strategy Lab rule. Stored as plain text; unreadable lines are skipped, never crash. */
data class RuleSpec(
    val id: String,
    val name: String,
    val useEma: Boolean = true, val emaFast: Int = 21, val emaSlow: Int = 50,
    val useRsi: Boolean = true, val rsiLo: Double = 45.0, val rsiHi: Double = 65.0,
    val useAdx: Boolean = true, val adxMin: Double = 20.0,
    val callSide: Boolean = true,
    val status: LabStatus = LabStatus.DRAFT
) {
    fun toRule(): StrategyRule {
        val c = ArrayList<RuleCondition>()
        if (useEma) c += if (callSide) RuleCondition.EmaAbove(emaFast, emaSlow) else RuleCondition.EmaBelow(emaFast, emaSlow)
        if (useRsi) c += RuleCondition.RsiBetween(rsiLo, rsiHi)
        if (useAdx) c += RuleCondition.AdxAtLeast(adxMin)
        return StrategyRule(id, name, c, status, callSide)
    }

    fun describe(): String = toRule().conditions.joinToString(" + ") { it.label }.ifEmpty { "(no conditions)" } + if (callSide) "  -> CALL side" else "  -> PUT side"

    /** The base rule plus each numeric parameter moved one step either way. */
    fun neighbours(): Map<String, RuleSpec> {
        val m = LinkedHashMap<String, RuleSpec>()
        m["base"] = this
        if (useRsi) {
            if (rsiLo - 3 >= 0) m["RSI lo-3"] = copy(rsiLo = rsiLo - 3)
            if (rsiLo + 3 < rsiHi) m["RSI lo+3"] = copy(rsiLo = rsiLo + 3)
            if (rsiHi - 3 > rsiLo) m["RSI hi-3"] = copy(rsiHi = rsiHi - 3)
            if (rsiHi + 3 <= 100) m["RSI hi+3"] = copy(rsiHi = rsiHi + 3)
        }
        if (useAdx) {
            if (adxMin - 5 >= 0) m["ADX-5"] = copy(adxMin = adxMin - 5)
            m["ADX+5"] = copy(adxMin = adxMin + 5)
        }
        if (useEma) {
            val periods = listOf(9, 21, 50, 100, 200)
            val idx = periods.indexOf(emaSlow)
            if (idx > 0 && periods[idx - 1] > emaFast) m["EMA slow down"] = copy(emaSlow = periods[idx - 1])
            if (idx in 0 until periods.lastIndex) m["EMA slow up"] = copy(emaSlow = periods[idx + 1])
        }
        return m
    }

    fun encode(): String = listOf(
        id.clean(), name.clean(), useEma, emaFast, emaSlow, useRsi, rsiLo, rsiHi, useAdx, adxMin, callSide, status.name
    ).joinToString("|")

    companion object {
        private fun String.clean() = replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()

        fun decode(line: String): RuleSpec? = try {
            val f = line.split("|")
            if (f.size != 12) null else RuleSpec(
                f[0], f[1], f[2].toBooleanStrict(), f[3].toInt(), f[4].toInt(), f[5].toBooleanStrict(), f[6].toDouble(), f[7].toDouble(),
                f[8].toBooleanStrict(), f[9].toDouble(), f[10].toBooleanStrict(), LabStatus.valueOf(f[11])
            ).takeIf { it.id.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}

object LabRuleStore {
    fun parse(text: String): List<RuleSpec> = text.lines().mapNotNull { if (it.isBlank()) null else RuleSpec.decode(it) }
    fun serialize(rules: List<RuleSpec>): String = rules.joinToString("\n") { it.encode() }
}

data class PaperStat(val trades: Int, val wins: Int, val pnl: Double) {
    val expectancy: Double get() = if (trades == 0) 0.0 else pnl / trades
}

/**
 * Forward (paper) observation of Strategy Lab rules on live candles. A rule's trade is entered at the CLOSE of the
 * newest candle and settled at the candle that opens at entry + expiry; nothing is peeked. Virtual only.
 */
class LabPaperTracker(
    private val expiryCandles: Int,
    private val candleMs: Long,
    private val payout: Double = 0.85,
    private val cap: Int = 300
) {
    private class Pend(val ruleId: String, val call: Boolean, val entry: Double, val expiryMs: Long)

    private val pend = ArrayList<Pend>()
    private val stats = HashMap<String, PaperStat>()

    @Synchronized fun stat(id: String): PaperStat = stats[id] ?: PaperStat(0, 0, 0.0)

    @Synchronized fun reset(id: String) {
        stats.remove(id)
        pend.removeAll { it.ruleId == id }
    }

    @Synchronized fun load(text: String) {
        stats.clear()
        stats.putAll(parse(text))
    }

    @Synchronized fun dump(): String = stats.entries.joinToString("\n") { "${it.key}|${it.value.trades}|${it.value.wins}|${it.value.pnl}" }

    /** Returns true when any statistic changed. */
    @Synchronized fun onCandleClosed(rules: List<StrategyRule>, candles: List<Candle>): Boolean {
        if (candles.isEmpty()) return false
        var changed = false
        val newest = candles.last().openTimeMs
        val iter = pend.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            val exit = candles.lastOrNull { c -> c.openTimeMs == p.expiryMs }
            if (exit != null) {
                val pnl = when {
                    exit.close == p.entry -> 0.0
                    (exit.close > p.entry) == p.call -> payout
                    else -> -1.0
                }
                val old = stat(p.ruleId)
                stats[p.ruleId] = PaperStat(old.trades + 1, old.wins + if (pnl > 0) 1 else 0, old.pnl + pnl)
                iter.remove(); changed = true
            } else if (newest > p.expiryMs + candleMs * 3) iter.remove() // feed gap: drop, never guess
        }
        val series = PriceSeries.window(candles, candles.size, cap)
        for (r in rules) {
            if (pend.any { it.ruleId == r.id }) continue
            if (r.matches(series)) {
                pend.add(Pend(r.id, r.callSide, candles.last().close, newest + expiryCandles * candleMs))
            }
        }
        return changed
    }

    companion object {
        fun parse(text: String): Map<String, PaperStat> {
            val m = HashMap<String, PaperStat>()
            for (line in text.lines()) {
                val f = line.split("|")
                if (f.size != 4) continue
                try { m[f[0]] = PaperStat(f[1].toInt(), f[2].toInt(), f[3].toDouble()) } catch (e: Exception) { /* skip corrupt line */ }
            }
            return m
        }
    }
}

/** User-entered calendar events: "timeMs|IMPACT|title" per line. Corrupt lines are skipped. */
object NewsEventCodec {
    fun parse(text: String): List<com.jarvis.assistant.quotex.agent.EconomicEvent> = text.lines().mapNotNull { line ->
        try {
            val f = line.split("|", limit = 3)
            if (f.size != 3) null else com.jarvis.assistant.quotex.agent.EconomicEvent(
                f[0].toLong(), f[2], com.jarvis.assistant.quotex.agent.EventImpact.valueOf(f[1])
            )
        } catch (e: Exception) {
            null
        }
    }

    fun serialize(events: List<com.jarvis.assistant.quotex.agent.EconomicEvent>): String =
        events.joinToString("\n") { "${it.timeMs}|${it.impact.name}|${it.title.replace('\n', ' ').replace('|', ' ')}" }
}
