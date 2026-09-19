package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class BacktestEngineTest {

    private fun trade(id: String, pnl: Double, exitTime: Long) = BacktestTrade(
        signalId = id, pair = CurrencyPair.EURUSD, direction = TradeDirection.BUY,
        entryTimeEpochMillis = exitTime - 1000, entryPrice = 1.10, exitTimeEpochMillis = exitTime,
        exitPrice = 1.11, stopLoss = 1.095, takeProfit = 1.12, lotSize = 0.1,
        riskPercentOfEquity = 0.5, pnlAmount = pnl, exitReason = "TEST"
    )

    // --- pure aggregation math ---

    @Test
    fun aggregatesWinRateProfitFactorDrawdownAndStreaks() {
        val trades = listOf(
            trade("t1", 100.0, 1000L), trade("t2", 50.0, 2000L), trade("t3", -30.0, 3000L),
            trade("t4", -20.0, 4000L), trade("t5", -10.0, 5000L), trade("t6", 200.0, 6000L)
        )
        val result = trades.toBacktestResult(startingBalance = 10_000.0)

        assertEquals(6, result.totalTrades)
        assertEquals(3, result.wins)
        assertEquals(3, result.losses)
        assertEquals(50.0, result.winRatePercent, 1e-9)
        assertEquals(350.0 / 60.0, result.profitFactor!!, 1e-6)
        assertEquals(290.0 / 6.0, result.expectancy, 1e-6)
        assertEquals(60.0 / 10150.0 * 100.0, result.maxDrawdownPercent, 1e-6) // peak 10150 after t2, trough 10090 after t5
        assertEquals(3, result.maxConsecutiveLosses)
        assertEquals(290.0, result.netPnl, 1e-9)
        assertEquals(10_290.0, result.endingBalance, 1e-9)
    }

    @Test
    fun handlesNoTradesGracefully() {
        val result = emptyList<BacktestTrade>().toBacktestResult(10_000.0)
        assertEquals(0, result.totalTrades)
        assertEquals(0.0, result.winRatePercent, 1e-9)
        assertNull(result.profitFactor)
        assertEquals(0.0, result.expectancy, 1e-9)
        assertEquals(10_000.0, result.endingBalance, 1e-9)
    }

    @Test
    fun profitFactorIsNullWithNoLosingTrades() {
        val result = listOf(trade("t1", 50.0, 1000L), trade("t2", 30.0, 2000L)).toBacktestResult(10_000.0)
        assertNull(result.profitFactor) // grossLoss == 0 -> undefined, not infinity or zero
    }

    // --- end-to-end smoke test against the real engine ---

    private fun candle(open: Double, high: Double, low: Double, close: Double, index: Long) =
        Candle(CurrencyPair.EURUSD, Timeframe.M5, index, open, high, low, close, null, DataSource.DEMO_SIMULATED)

    /** A gently rising zigzag: net upward drift with a period-8 oscillation layered on top, so
     * the real fractal swing detector (unlike the hand-crafted structure snapshots used in other
     * engines' tests) actually finds genuine higher-highs/higher-lows rather than nothing at all
     * (a pure monotonic ramp has no local extrema and would never classify as trending). */
    private fun zigzagUptrend(count: Int): List<Candle> {
        var prevClose = 1.0000
        return (0 until count).map { i ->
            val base = 1.0000 + i * 0.0004
            val oscillation = 0.0020 * sin(2.0 * Math.PI * i / 8.0)
            val close = base + oscillation
            val open = prevClose
            val high = maxOf(open, close) + 0.0003
            val low = minOf(open, close) - 0.0003
            prevClose = close
            candle(open, high, low, close, i.toLong() * 300_000L)
        }
    }

    @Test
    fun runProducesInternallyConsistentResultsWithoutThrowing() {
        val candles = zigzagUptrend(160)
        val config = BacktestConfig(
            pair = CurrencyPair.EURUSD,
            confluenceStack = listOf(Timeframe.M5), // single-timeframe stack keeps this test self-contained
            historicalCandlesByTimeframe = mapOf(Timeframe.M5 to candles),
            backtestStartEpochMillis = candles[60].openTimeEpochMillis,
            backtestEndEpochMillis = candles.last().openTimeEpochMillis,
            startingBalance = 10_000.0,
            settings = TradingSettings(),
            warmupBars = 60
        )

        val result = BacktestEngine().run(config)

        assertEquals(result.trades.size, result.totalTrades)
        assertEquals(result.wins + result.losses, result.totalTrades)
        assertEquals(result.endingBalance, config.startingBalance + result.netPnl, 1e-6)
        if (result.totalTrades > 0) {
            assertTrue(result.winRatePercent in 0.0..100.0)
            assertTrue(result.maxDrawdownPercent >= 0.0)
        }
    }

    @Test
    fun inSampleAndOutOfSampleSplitProducesTwoWellFormedResults() {
        val candles = zigzagUptrend(200)
        val config = BacktestConfig(
            pair = CurrencyPair.EURUSD,
            confluenceStack = listOf(Timeframe.M5),
            historicalCandlesByTimeframe = mapOf(Timeframe.M5 to candles),
            backtestStartEpochMillis = candles[60].openTimeEpochMillis,
            backtestEndEpochMillis = candles.last().openTimeEpochMillis,
            settings = TradingSettings(),
            warmupBars = 60
        )
        val splitTime = candles[130].openTimeEpochMillis

        val (inSample, outOfSample) = BacktestEngine().runInSampleAndOutOfSample(config, splitTime)

        assertEquals(inSample.endingBalance, config.startingBalance + inSample.netPnl, 1e-6)
        assertEquals(outOfSample.endingBalance, config.startingBalance + outOfSample.netPnl, 1e-6)
    }

    @Test(expected = IllegalStateException::class)
    fun throwsWhenEntryTimeframeHistoryIsMissing() {
        val config = BacktestConfig(
            pair = CurrencyPair.EURUSD,
            confluenceStack = listOf(Timeframe.M5),
            historicalCandlesByTimeframe = emptyMap(), // no M5 history supplied
            backtestStartEpochMillis = 0L, backtestEndEpochMillis = 1L,
            settings = TradingSettings()
        )
        BacktestEngine().run(config)
    }
}
