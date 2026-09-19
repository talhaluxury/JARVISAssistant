package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test

class SignalConfidenceEngineTest {

    private val engine = SignalConfidenceEngine()
    private val settings = TradingSettings()

    private fun candle(open: Double, high: Double, low: Double, close: Double, index: Long) =
        Candle(CurrencyPair.EURUSD, Timeframe.M5, index, open, high, low, close, null, DataSource.DEMO_SIMULATED)

    /** 40 steadily rising candles: close_i = 1.0 + i*0.001 (+/- a fixed small body/wick), giving
     * a clean uptrend with constant true range (~0.0010) so ATR-based expectations below are
     * exact rather than approximate. */
    private fun risingCandles(count: Int = 40): List<Candle> = (0 until count).map { i ->
        val base = 1.0 + i * 0.001
        val open = base - 0.0003
        val close = base + 0.0003
        candle(open, close + 0.0002, open - 0.0002, close, i.toLong() * 300_000L)
    }

    private fun mtfSnapshot(tf: Timeframe, regime: MarketRegime) = TimeframeStructureSnapshot(
        timeframe = tf,
        structure = MarketStructureSnapshot(regime, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        candles = emptyList()
    )

    private fun goodEntryStructure(currentPrice: Double, atr: Double): MarketStructureSnapshot {
        val support = SupportResistanceLevel(currentPrice - atr * 1.5, LevelType.SUPPORT, touches = 3, lastTouchIndex = 10)
        val resistance = SupportResistanceLevel(currentPrice + atr * 5.0, LevelType.RESISTANCE, touches = 2, lastTouchIndex = 20)
        val supportingBreakout = StructureEventResult(
            StructureEvent.BREAKOUT,
            SupportResistanceLevel(currentPrice - atr, LevelType.RESISTANCE, touches = 2, lastTouchIndex = 39),
            candleIndex = 39, detail = "test breakout"
        )
        return MarketStructureSnapshot(
            regime = MarketRegime.TRENDING_UP,
            swings = emptyList(), labeledSwings = emptyList(),
            supportLevels = listOf(support), resistanceLevels = listOf(resistance),
            recentEvents = listOf(supportingBreakout)
        )
    }

    private fun tick(mid: Double, spreadAbs: Double) = PriceTick(
        pair = CurrencyPair.EURUSD, bid = mid - spreadAbs / 2, ask = mid + spreadAbs / 2,
        timestampEpochMillis = 1_000_000L, source = DataSource.DEMO_SIMULATED
    )

    @Test
    fun returnsMarketUnavailableWhenTickIsInvalid() {
        val candles = risingCandles()
        val result = engine.generate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.H1, MarketRegime.TRENDING_UP)),
            entryCandles = candles,
            entryStructure = goodEntryStructure(1.039, 0.001),
            latestTick = tick(1.039, 0.0002),
            tickQuality = DataQuality.Invalid("Stale data for EURUSD: 90s old."),
            settings = settings
        )
        assertEquals(TradeDecision.MARKET_UNAVAILABLE, result.decision)
    }

    @Test
    fun returnsNoTradeWhenTimeframesConflict() {
        val candles = risingCandles()
        val result = engine.generate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(
                mtfSnapshot(Timeframe.H4, MarketRegime.TRENDING_UP),
                mtfSnapshot(Timeframe.H1, MarketRegime.TRENDING_DOWN)
            ),
            entryCandles = candles,
            entryStructure = goodEntryStructure(1.039, 0.001),
            latestTick = tick(1.039, 0.0002),
            tickQuality = DataQuality.Valid,
            settings = settings
        )
        assertEquals(TradeDecision.NO_TRADE, result.decision)
        assertTrue(result.reason.contains("disagree", ignoreCase = true))
    }

    @Test
    fun returnsWaitWhenNoTimeframeShowsADirection() {
        val candles = risingCandles()
        val result = engine.generate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(
                mtfSnapshot(Timeframe.H4, MarketRegime.RANGING),
                mtfSnapshot(Timeframe.H1, MarketRegime.UNCERTAIN)
            ),
            entryCandles = candles,
            entryStructure = goodEntryStructure(1.039, 0.001),
            latestTick = tick(1.039, 0.0002),
            tickQuality = DataQuality.Valid,
            settings = settings
        )
        assertEquals(TradeDecision.WAIT, result.decision)
    }

    @Test
    fun goodConfluenceProducesStrongBuySetup() {
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val atr = 0.0010 // matches the constant true range built into risingCandles()
        val result = engine.generate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(
                mtfSnapshot(Timeframe.D1, MarketRegime.TRENDING_UP),
                mtfSnapshot(Timeframe.H4, MarketRegime.TRENDING_UP),
                mtfSnapshot(Timeframe.H1, MarketRegime.TRENDING_UP)
            ),
            entryCandles = candles,
            entryStructure = goodEntryStructure(currentPrice, atr),
            latestTick = tick(currentPrice, spreadAbs = 0.00012), // 1.2 pips, well under the 3.0 pip default max
            tickQuality = DataQuality.Valid,
            settings = settings
        )
        assertTrue(
            "expected a BUY-side setup, got ${result.decision} (score ${result.confidenceScore}, reason: ${result.reason})",
            result.decision == TradeDecision.BUY_SETUP || result.decision == TradeDecision.STRONG_BUY_SETUP
        )
        assertTrue(result.confidenceScore >= settings.minConfidenceScoreToTrade)
        assertNotNull(result.entry)
        assertNotNull(result.stopLoss)
        assertNotNull(result.takeProfit)
        assertNotNull(result.riskRewardRatio)
        assertTrue(result.riskRewardRatio!! >= settings.risk.minRiskRewardRatio)
    }

    @Test
    fun excessiveSpreadForcesNoTradeDespiteOtherwiseGoodConfluence() {
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val atr = 0.0010
        val result = engine.generate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(
                mtfSnapshot(Timeframe.D1, MarketRegime.TRENDING_UP),
                mtfSnapshot(Timeframe.H4, MarketRegime.TRENDING_UP)
            ),
            entryCandles = candles,
            entryStructure = goodEntryStructure(currentPrice, atr),
            latestTick = tick(currentPrice, spreadAbs = 0.0006), // 6 pips, above the 3.0 pip default max
            tickQuality = DataQuality.Valid,
            settings = settings
        )
        assertEquals(TradeDecision.NO_TRADE, result.decision)
        assertTrue(result.reason.contains("Spread", ignoreCase = true))
    }

    @Test
    fun poorRiskRewardForcesNoTrade() {
        val candles = risingCandles()
        val currentPrice = candles.last().close
        val atr = 0.0010
        // resistance placed very close (0.3x ATR) -> reward far too small vs. the calculated risk
        val cramped = MarketStructureSnapshot(
            regime = MarketRegime.TRENDING_UP,
            swings = emptyList(), labeledSwings = emptyList(),
            supportLevels = listOf(SupportResistanceLevel(currentPrice - atr * 1.5, LevelType.SUPPORT, 3, 10)),
            resistanceLevels = listOf(SupportResistanceLevel(currentPrice + atr * 0.3, LevelType.RESISTANCE, 2, 20)),
            recentEvents = emptyList()
        )
        val result = engine.generate(
            pair = CurrencyPair.EURUSD,
            mtfSnapshots = listOf(mtfSnapshot(Timeframe.H4, MarketRegime.TRENDING_UP)),
            entryCandles = candles,
            entryStructure = cramped,
            latestTick = tick(currentPrice, spreadAbs = 0.00012),
            tickQuality = DataQuality.Valid,
            settings = settings
        )
        assertEquals(TradeDecision.NO_TRADE, result.decision)
    }
}
