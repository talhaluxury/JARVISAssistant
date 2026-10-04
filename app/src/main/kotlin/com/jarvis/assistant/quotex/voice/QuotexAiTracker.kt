package com.jarvis.assistant.quotex.voice

import com.jarvis.assistant.quotex.domain.Candle

/** One AI opinion (demo practice) and, once its expiry has passed, whether the stored candles proved it right. */
data class AiCall(val timeMs: Long, val up: Boolean, val entry: Double, val expiryMs: Long, var won: Boolean? = null, var draw: Boolean = false)

/**
 * Keeps score of the AI's own opinions with the chart's stored candles - no guessing: a call is resolved with the close of
 * the first stored candle that ends at or after its expiry, compared with the price when it was given.
 */
class QuotexAiTracker(private val maxKept: Int = 200) {
    private val calls = ArrayList<AiCall>()

    fun add(call: AiCall) { calls.add(call); while (calls.size > maxKept) calls.removeAt(0) }

    fun resolve(candles: List<Candle>, candleMs: Long) {
        for (c in calls) {
            if (c.won != null || c.draw) continue
            val closing = candles.firstOrNull { it.openTimeMs + candleMs >= c.expiryMs && it.openTimeMs >= c.timeMs - candleMs } ?: continue
            if (closing.openTimeMs + candleMs > System.currentTimeMillis() + candleMs) continue
            when {
                closing.close == c.entry -> c.draw = true
                else -> c.won = (closing.close > c.entry) == c.up
            }
        }
    }

    val pending: Int get() = calls.count { it.won == null && !it.draw }
    val wins: Int get() = calls.count { it.won == true }
    val losses: Int get() = calls.count { it.won == false }

    fun summary(breakEven: Double): String {
        val done = wins + losses
        if (done == 0) return "AI record: no finished opinions yet ($pending waiting for their expiry). Ask 'ai analyze' on the demo account and check back."
        val rate = wins * 100.0 / done
        val verdict = when {
            done < 30 -> "far too few to mean anything (need 100+)"
            rate < breakEven * 100 -> "BELOW the break-even of ${"%.1f".format(breakEven * 100)}%: following these would lose money"
            else -> "above break-even for now, but ${if (done < 100) "only $done results - luck is still likely" else "keep measuring"}"
        }
        return "AI record: $wins right, $losses wrong of $done finished ($pending pending): ${"%.0f".format(rate)}% - $verdict."
    }
}
