package com.jarvis.assistant.trading.oanda

import com.jarvis.assistant.trading.CurrencyPair
import com.jarvis.assistant.trading.Timeframe
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * See docs/OANDA_INTEGRATION_CHECKLIST.md for how and why to run this.
 *
 * Every test here talks to a REAL OANDA practice account over the network — none of it runs in
 * this project's normal (offline) unit test pass. Each test starts with an [assumeTrue] that
 * skips it (not fails it) unless both OANDA_API_KEY and OANDA_ACCOUNT_ID are set in the
 * environment, so this file is safe to leave committed and running in CI permanently: it's a
 * no-op everywhere except a machine that deliberately opted in.
 *
 * These tests intentionally print what they fetched rather than asserting on every field — the
 * point is to let a human eyeball real OANDA responses next to OandaApi.kt's assumptions (see
 * the checklist), not to encode assumptions that might themselves be wrong into more assertions.
 */
class OandaLiveIntegrationTest {

    private val apiKey: String? = System.getenv("OANDA_API_KEY")
    private val accountId: String? = System.getenv("OANDA_ACCOUNT_ID")

    private fun requireCredentials() {
        assumeTrue(
            "Set OANDA_API_KEY and OANDA_ACCOUNT_ID to run this against a real practice account — see docs/OANDA_INTEGRATION_CHECKLIST.md",
            apiKey != null && accountId != null
        )
    }

    private fun buildApi(): OandaApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(OandaEnvironment.PRACTICE.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(OandaApi::class.java)
    }

    @Test
    fun accountSummaryMatchesExpectedShape() = runBlocking {
        requireCredentials()
        val adapter = OandaBrokerAdapter(buildApi(), accountId!!, OandaEnvironment.PRACTICE)
        val account = adapter.account()
        assertNotNull(
            "account() returned null — likely means OandaAccount's field names don't match the real response; " +
                "re-run the curl command in checklist §2 and compare.",
            account
        )
        println("[OANDA] account: $account")
    }

    @Test
    fun latestTickForEurUsdHasSaneBidAskOrdering() = runBlocking {
        requireCredentials()
        val adapter = OandaBrokerAdapter(buildApi(), accountId!!, OandaEnvironment.PRACTICE)
        val tick = adapter.latestTick(CurrencyPair.EURUSD)
        assertNotNull("latestTick() returned null for EURUSD — check the pricing response shape", tick)
        println("[OANDA] EURUSD tick: $tick")
        assertTrue(
            "ask (${tick!!.ask}) should be >= bid (${tick.bid}) — if this fails, the bid[0]/ask[0] " +
                "\"best price\" assumption in latestTick() is backwards; see checklist §3",
            tick.ask >= tick.bid
        )
    }

    @Test
    fun candlesReturnRecentCompletedHistory() = runBlocking {
        requireCredentials()
        val adapter = OandaBrokerAdapter(buildApi(), accountId!!, OandaEnvironment.PRACTICE)
        val candles = adapter.candles(CurrencyPair.EURUSD, Timeframe.M15, 10)
        assertTrue("Expected at least one completed M15 candle for EURUSD", candles.isNotEmpty())
        println("[OANDA] last EURUSD M15 candle: ${candles.last()}")
        candles.forEach {
            assertTrue("candle high (${it.high}) should be >= low (${it.low})", it.high >= it.low)
        }
    }

    @Test
    fun openPositionsPrintsRealShapeForManualStopLossTakeProfitVerification() = runBlocking {
        requireCredentials()
        val adapter = OandaBrokerAdapter(buildApi(), accountId!!, OandaEnvironment.PRACTICE)
        val positions = adapter.openPositions()
        println(
            "[OANDA] open positions (stopLoss/takeProfit are hardcoded to 0.0 until checklist §3's " +
                "field-name check is done — verify manually against your practice account's real open trades): $positions"
        )
    }
}
