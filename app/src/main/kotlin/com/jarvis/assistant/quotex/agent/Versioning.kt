package com.jarvis.assistant.quotex.agent

/** Section 36: every signal carries these so any historical result can be reproduced. */
object AnalysisVersions {
    const val STRATEGY_VERSION = "2.0.0"
    const val ANALYSIS_VERSION = "2.0.0"
}

data class IndicatorSettings(
    val emaPeriods: List<Int> = listOf(9, 21, 50, 100, 200),
    val rsiPeriod: Int = 14,
    val macd: String = "12/26/9",
    val bollinger: String = "20/2.0",
    val atrPeriod: Int = 14,
    val adxPeriod: Int = 14,
    val stochastic: String = "default"
) {
    fun describe(): String =
        "ema=${emaPeriods.joinToString("/")};rsi=$rsiPeriod;macd=$macd;bb=$bollinger;atr=$atrPeriod;adx=$adxPeriod;stoch=$stochastic"
}
