package com.jarvis.assistant.quotex.analysis

import com.jarvis.assistant.wingo.analysis.ModelOutput
import com.jarvis.assistant.wingo.analysis.laplace

/** A model's estimate of P(price is higher [horizon] candles from now). ModelOutput.probBig = P(higher). */
interface QuotexModel {
    val name: String
    fun predict(series: PriceSeries, horizon: Int): ModelOutput
}

/**
 * Data-driven state model: it classifies every past candle into a state (e.g. "RSI 55-70"), then looks at
 * what price did [horizon] candles after past candles in the SAME state as now. It only uses outcomes that
 * were already known at prediction time (index + horizon <= last candle).
 */
class StateModel(
    override val name: String,
    private val describe: (Int) -> String,
    private val stateAt: (PriceSeries, Int) -> Int?
) : QuotexModel {

    private val minOccurrences = 30

    override fun predict(series: PriceSeries, horizon: Int): ModelOutput {
        val n = series.size
        if (n < horizon + 30) return ModelOutput.abstain(name, n, "Needs ${horizon + 30} candles, have $n")
        val current = stateAt(series, n - 1) ?: return ModelOutput.abstain(name, n, "Indicator not ready yet")
        var occurrences = 0
        var higher = 0
        for (i in 0 until n - horizon) {
            val state = stateAt(series, i) ?: continue
            if (state != current) continue
            val later = series.closes[i + horizon]
            val now = series.closes[i]
            if (later == now) continue
            occurrences++
            if (later > now) higher++
        }
        if (occurrences < minOccurrences) return ModelOutput.abstain(name, occurrences, "Only $occurrences comparable past cases")
        return ModelOutput(
            name, laplace(higher, occurrences), occurrences,
            "${describe(current)}: price was higher $horizon candles later in $higher of $occurrences past cases"
        )
    }
}

private fun bit(series: PriceSeries, i: Int): Int = if (series.up[i]) 1 else 0

fun defaultQuotexModels(): List<QuotexModel> = listOf(
    StateModel("EMA Trend", { if (it == 1) "EMA9 above EMA21" else "EMA9 below EMA21" }) { s, i ->
        if (s.ema9[i] > s.ema21[i]) 1 else 0
    },
    StateModel("RSI Zone", { "RSI zone $it (0=oversold .. 4=overbought)" }) { s, i ->
        val r = s.rsi14[i]
        if (r.isNaN()) null else when {
            r < 30 -> 0
            r < 45 -> 1
            r < 55 -> 2
            r < 70 -> 3
            else -> 4
        }
    },
    StateModel("Bollinger", { "Bollinger zone $it (0=below lower band .. 4=above upper band)" }) { s, i ->
        val b = s.percentB[i]
        if (b.isNaN()) null else when {
            b < 0.0 -> 0
            b < 0.2 -> 1
            b <= 0.8 -> 2
            b <= 1.0 -> 3
            else -> 4
        }
    },
    StateModel("Momentum", { if (it == 1) "Price above 5 candles ago" else "Price below 5 candles ago" }) { s, i ->
        if (i < 5) null else if (s.closes[i] > s.closes[i - 5]) 1 else 0
    },
    StateModel("Last 3 Candles", { code -> "Last 3 candles " + (2 downTo 0).joinToString("") { if ((code shr it) and 1 == 1) "U" else "D" } }) { s, i ->
        if (i < 2) null else bit(s, i - 2) * 4 + bit(s, i - 1) * 2 + bit(s, i)
    },
    StateModel("Recent Drift", { "Up-candle share bucket $it (0=mostly down .. 4=mostly up)" }) { s, i ->
        if (i < 19) {
            null
        } else {
            var ups = 0
            for (j in i - 19..i) ups += bit(s, j)
            ups * 5 / 21
        }
    }
)
