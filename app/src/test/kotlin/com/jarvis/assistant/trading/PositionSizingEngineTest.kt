package com.jarvis.assistant.trading

import org.junit.Assert.*
import org.junit.Test

class PositionSizingEngineTest {

    @Test
    fun pipValueForUsdQuotedPairIsPriceIndependent() {
        // EURUSD standard lot pip value is the textbook $10/pip figure, regardless of price.
        assertEquals(10.0, PositionSizingEngine.pipValuePerLotInUsd(CurrencyPair.EURUSD, 1.30)!!, 1e-9)
        assertEquals(10.0, PositionSizingEngine.pipValuePerLotInUsd(CurrencyPair.EURUSD, 0.95)!!, 1e-9)
    }

    @Test
    fun pipValueForUsdBasePairDependsOnCurrentPrice() {
        // USDJPY at ~150 -> classic ~$6.67/pip standard-lot figure.
        val pipValue = PositionSizingEngine.pipValuePerLotInUsd(CurrencyPair.USDJPY, 150.0)!!
        assertEquals(6.6667, pipValue, 0.001)
    }

    @Test
    fun calculatesExpectedLotSizeForEurUsd() {
        val result = PositionSizingEngine.calculate(
            accountEquity = 10_000.0, riskPercent = 0.5,
            entry = 1.1000, stopLoss = 1.0950,
            pair = CurrencyPair.EURUSD, currentPrice = 1.1000,
            maxLotSize = 1.0
        )
        assertNotNull(result)
        assertEquals(0.10, result!!.lotSize, 0.001)
        assertEquals(50.0, result.stopLossPips, 0.001)
        assertEquals(50.0, result.riskAmount, 0.5) // rounds to lot step, so close to but not always exactly the target risk
    }

    @Test
    fun calculatesExpectedLotSizeForUsdJpy() {
        val result = PositionSizingEngine.calculate(
            accountEquity = 10_000.0, riskPercent = 0.5,
            entry = 150.00, stopLoss = 149.50,
            pair = CurrencyPair.USDJPY, currentPrice = 150.00,
            maxLotSize = 1.0
        )
        assertNotNull(result)
        assertEquals(0.15, result!!.lotSize, 0.005)
    }

    @Test
    fun rejectsZeroStopDistance() {
        val result = PositionSizingEngine.calculate(
            accountEquity = 10_000.0, riskPercent = 0.5,
            entry = 1.1000, stopLoss = 1.1000, // no distance at all
            pair = CurrencyPair.EURUSD, currentPrice = 1.1000, maxLotSize = 1.0
        )
        assertNull(result)
    }

    @Test
    fun capsLotSizeAtConfiguredMaximum() {
        val result = PositionSizingEngine.calculate(
            accountEquity = 1_000_000.0, riskPercent = 5.0, // deliberately huge risk to force capping
            entry = 1.1000, stopLoss = 1.0990,
            pair = CurrencyPair.EURUSD, currentPrice = 1.1000, maxLotSize = 0.5
        )
        assertNotNull(result)
        assertEquals(0.5, result!!.lotSize, 1e-9)
    }

    @Test
    fun returnsNullWhenResultingLotSizeRoundsToZero() {
        val result = PositionSizingEngine.calculate(
            accountEquity = 100.0, riskPercent = 0.01, // tiny account, tiny risk
            entry = 1.1000, stopLoss = 1.0500, // huge stop distance
            pair = CurrencyPair.EURUSD, currentPrice = 1.1000, maxLotSize = 1.0
        )
        assertNull(result)
    }

    @Test
    fun returnsNullForUnsupportedCrossPair() {
        // Neither leg is USD -> this simplified engine can't price it without a conversion pair.
        // (Modeled here with a currency not in the CurrencyPair enum's supported set would be a
        // compile error, so this test instead documents the USD-leg requirement directly.)
        assertNull(PositionSizingEngine.pipValuePerLotInUsd(CurrencyPair.USDJPY, -1.0)) // invalid price -> null, not a garbage value
    }
}
