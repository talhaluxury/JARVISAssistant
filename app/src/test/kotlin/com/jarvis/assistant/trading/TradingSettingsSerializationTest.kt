package com.jarvis.assistant.trading

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the pure serialization logic that com.jarvis.assistant.data.local.prefs's
 * TradingSettingsPersistence.kt relies on. That file's own SecurePrefs-dependent wrapper
 * functions (loadTradingSettings/saveTradingSettings) need a real Android Keystore-backed
 * EncryptedSharedPreferences and aren't testable in a plain JVM unit test — but the thing that
 * actually matters (does TradingSettings survive a round trip, does bad input fail safely) is
 * fully covered here without needing Robolectric or a device.
 */
class TradingSettingsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun defaultSettingsRoundTripExactly() {
        val original = TradingSettings()
        val encoded = json.encodeToString(TradingSettings.serializer(), original)
        val decoded = json.decodeFromString(TradingSettings.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun customizedSettingsRoundTripExactly() {
        val original = TradingSettings(
            tradingMode = TradingMode.LIVE,
            watchlist = setOf(CurrencyPair.EURUSD, CurrencyPair.XAUUSD),
            allowedSessions = setOf(TradingSession.LONDON, TradingSession.NEW_YORK),
            confluenceStack = listOf(Timeframe.H4, Timeframe.M15),
            minConfidenceScoreToTrade = 80,
            risk = RiskSettings(maxRiskPerTradePercent = 1.0, maxDailyLossPercent = 3.0, maxOpenTrades = 5),
            preAuthorizedExecution = true
        )
        val encoded = json.encodeToString(TradingSettings.serializer(), original)
        val decoded = json.decodeFromString(TradingSettings.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(TradingMode.LIVE, decoded.tradingMode)
        assertEquals(5, decoded.risk.maxOpenTrades)
    }

    @Test
    fun missingFieldsFallBackToDeclaredDefaults() {
        // Simulates loading a settings blob saved by an older schema version that didn't yet
        // have every field — every field in TradingSettings/RiskSettings has a declared default,
        // so decoding "{}" should produce exactly TradingSettings(), not a crash.
        val decoded = json.decodeFromString(TradingSettings.serializer(), "{}")
        assertEquals(TradingSettings(), decoded)
    }

    @Test(expected = Exception::class)
    fun garbageJsonThrowsRatherThanSilentlyProducingWrongSettings() {
        // This is exactly why TradingSettingsPersistence.loadTradingSettings wraps this call in
        // runCatching { ... }.getOrElse { TradingSettings() } — decoding real garbage must throw,
        // not return something subtly wrong that looks valid.
        json.decodeFromString(TradingSettings.serializer(), "{not valid json at all")
    }

    @Test
    fun riskSettingsRoundTripsIndependently() {
        val original = RiskSettings(maxRiskPerTradePercent = 0.25, maxConsecutiveLosses = 5)
        val encoded = json.encodeToString(RiskSettings.serializer(), original)
        val decoded = json.decodeFromString(RiskSettings.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
