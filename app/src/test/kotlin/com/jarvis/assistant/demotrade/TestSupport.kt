package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle

internal const val T0 = CandleSimulator.START_MS
internal const val MIN_MS = CandleSimulator.CANDLE_MS

internal fun testSettings(block: (DemoSettings) -> DemoSettings = { it }): DemoSettings =
    block(DemoSettings(autoDemoTrading = true, useAi = false)).coerced()

internal fun flatCandles(n: Int, price: Double = 1.1, start: Long = T0): List<Candle> =
    List(n) { Candle(start + it * MIN_MS, price, price + 0.0001, price - 0.0001, price) }

internal fun signal(
    dir: Dir = Dir.CALL,
    confidence: Int = 80,
    now: Long = T0,
    entry: Double = 1.1,
    regime: Regime = Regime.TREND_UP,
    risk: RiskLevel = RiskLevel.MEDIUM,
    validForMs: Long = 20_000L,
    s: DemoSettings = testSettings()
): TradeSignal = TradeSignal(
    direction = dir,
    confidence = confidence,
    entryPrice = entry,
    timestampMs = now,
    candleTimeMs = now - MIN_MS,
    expirySeconds = s.expirySeconds,
    validUntilMs = now + validForMs,
    regime = regime,
    confirmations = listOf("test"),
    reasons = listOf("test reason"),
    riskLevel = risk,
    stake = 0.0,
    strength = s.strengthOf(confidence),
    asset = "TEST",
    votes = listOf(StrategyVote("Trend Following", dir, confidence, listOf("r")))
)

internal class Clock(var now: Long = T0) {
    fun get(): Long = now
    fun advance(ms: Long) { now += ms }
}

internal fun engineWith(clock: Clock, s: DemoSettings = testSettings(), store: DemoStateStore? = null, events: MutableList<DemoEvent>? = null): DemoTradingEngine {
    val listener: ((DemoEvent, DemoSettings) -> Unit)? =
        if (events == null) null else fun(e: DemoEvent, _: DemoSettings) { events.add(e) }
    return DemoTradingEngine(s, store, { clock.get() }, listener)
}
