package com.jarvis.assistant.trading.oanda

import com.jarvis.assistant.trading.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class OandaBrokerAdapterTest {

    // --- pure helper functions, no network involved ---

    @Test
    fun instrumentCodeUsesOandaUnderscoreFormat() {
        assertEquals("EUR_USD", OandaBrokerAdapter.instrumentCode(CurrencyPair.EURUSD))
        assertEquals("USD_JPY", OandaBrokerAdapter.instrumentCode(CurrencyPair.USDJPY))
        assertEquals("XAU_USD", OandaBrokerAdapter.instrumentCode(CurrencyPair.XAUUSD))
    }

    @Test
    fun pairFromInstrumentRoundTripsForEveryConfiguredPair() {
        for (pair in CurrencyPair.entries) {
            val code = OandaBrokerAdapter.instrumentCode(pair)
            assertEquals(pair, OandaBrokerAdapter.pairFromInstrument(code))
        }
    }

    @Test
    fun pairFromInstrumentReturnsNullForUnrecognizedCode() {
        assertNull(OandaBrokerAdapter.pairFromInstrument("XYZ_ABC"))
        assertNull(OandaBrokerAdapter.pairFromInstrument("NOTVALID"))
    }

    @Test
    fun granularityCodeCoversEveryTimeframe() {
        assertEquals("M1", OandaBrokerAdapter.granularityCode(Timeframe.M1))
        assertEquals("M5", OandaBrokerAdapter.granularityCode(Timeframe.M5))
        assertEquals("M15", OandaBrokerAdapter.granularityCode(Timeframe.M15))
        assertEquals("H1", OandaBrokerAdapter.granularityCode(Timeframe.H1))
        assertEquals("H4", OandaBrokerAdapter.granularityCode(Timeframe.H4))
        assertEquals("D", OandaBrokerAdapter.granularityCode(Timeframe.D1))
    }

    @Test
    fun parseTimeHandlesRfc3339WithFractionalSeconds() {
        val millis = OandaBrokerAdapter.parseTime("2024-01-15T10:00:00.123456789Z")
        // Just past 10:00:00 on that date — exact value isn't the point, correctness of parsing is.
        assertTrue(millis > 0)
        val reparsedSameInstant = OandaBrokerAdapter.parseTime("2024-01-15T10:00:00.123Z")
        assertEquals(millis, reparsedSameInstant) // millisecond precision matches regardless of extra digits
    }

    @Test
    fun formatPriceUsesFiveDecimalPlaces() {
        assertEquals("1.08500", OandaBrokerAdapter.formatPrice(1.085))
        assertEquals("149.50000", OandaBrokerAdapter.formatPrice(149.5))
    }

    // --- adapter behavior against a fake OandaApi (no real network call) ---

    private class FakeOandaApi(
        val accountResponse: Response<OandaAccountResponse>? = null,
        val pricingResponse: Response<OandaPricingResponse>? = null,
        val candlesResponse: Response<OandaCandlesResponse>? = null,
        val orderResponse: Response<OandaOrderCreateResponse>? = null,
        val closeResponse: Response<OandaCloseTradeResponse>? = null
    ) : OandaApi {
        override suspend fun accountSummary(accountId: String) = accountResponse ?: error("not stubbed")
        override suspend fun pricing(accountId: String, instruments: String) = pricingResponse ?: error("not stubbed")
        override suspend fun candles(instrument: String, granularity: String, count: Int, price: String) =
            candlesResponse ?: error("not stubbed")
        override suspend fun createOrder(accountId: String, request: OandaCreateOrderRequest) = orderResponse ?: error("not stubbed")
        override suspend fun openTrades(accountId: String) = error("not stubbed in this test")
        override suspend fun closeTrade(accountId: String, tradeSpecifier: String) = closeResponse ?: error("not stubbed")
    }

    private fun <T> errorResponse(code: Int): Response<T> =
        Response.error(code, "".toResponseBody("application/json".toMediaType()))

    @Test
    fun accountMapsBalanceEquityAndMargin() = runBlocking {
        val api = FakeOandaApi(
            accountResponse = Response.success(
                OandaAccountResponse(OandaAccount(id = "001-001-123", balance = "10000.00", nav = "10050.25", marginUsed = "150.0", marginAvailable = "9900.25", currency = "USD"))
            )
        )
        val adapter = OandaBrokerAdapter(api, "001-001-123", OandaEnvironment.PRACTICE)
        val account = adapter.account()
        assertNotNull(account)
        assertEquals(10000.00, account!!.balance, 1e-9)
        assertEquals(10050.25, account.equity, 1e-9) // NAV maps to equity
        assertEquals(150.0, account.marginUsed, 1e-9)
    }

    @Test
    fun accountReturnsNullOnHttpFailureRatherThanThrowing() = runBlocking {
        val api = FakeOandaApi(accountResponse = errorResponse(500))
        val adapter = OandaBrokerAdapter(api, "001-001-123", OandaEnvironment.PRACTICE)
        assertNull(adapter.account())
    }

    @Test
    fun latestTickReadsFirstBidAndAsk() = runBlocking {
        val api = FakeOandaApi(
            pricingResponse = Response.success(
                OandaPricingResponse(listOf(
                    OandaPrice(
                        instrument = "EUR_USD", time = "2024-01-15T10:00:00.000000000Z",
                        bids = listOf(OandaPriceLevel("1.08500"), OandaPriceLevel("1.08495")),
                        asks = listOf(OandaPriceLevel("1.08520"), OandaPriceLevel("1.08525"))
                    )
                ))
            )
        )
        val adapter = OandaBrokerAdapter(api, "acc", OandaEnvironment.PRACTICE)
        val tick = adapter.latestTick(CurrencyPair.EURUSD)
        assertNotNull(tick)
        assertEquals(1.08500, tick!!.bid, 1e-9)
        assertEquals(1.08520, tick.ask, 1e-9)
        assertEquals(DataSource.BROKER_LIVE, tick.source)
    }

    @Test
    fun candlesFiltersIncompleteBarsAndMapsOhlc() = runBlocking {
        val api = FakeOandaApi(
            candlesResponse = Response.success(
                OandaCandlesResponse(
                    instrument = "EUR_USD", granularity = "M5",
                    candles = listOf(
                        OandaCandle(time = "2024-01-15T10:00:00.000000000Z", volume = 120, complete = true, mid = OandaOhlc("1.0850", "1.0855", "1.0848", "1.0852")),
                        OandaCandle(time = "2024-01-15T10:05:00.000000000Z", volume = 5, complete = false, mid = OandaOhlc("1.0852", "1.0853", "1.0851", "1.0852")) // in-progress bar
                    )
                )
            )
        )
        val adapter = OandaBrokerAdapter(api, "acc", OandaEnvironment.PRACTICE)
        val candles = adapter.candles(CurrencyPair.EURUSD, Timeframe.M5, 2)
        assertEquals(1, candles.size) // incomplete bar excluded
        assertEquals(1.0850, candles[0].open, 1e-9)
        assertEquals(1.0852, candles[0].close, 1e-9)
        assertEquals(DataSource.BROKER_HISTORICAL, candles[0].source)
    }

    @Test
    fun submitOrderMapsFillTransactionToFilledResult() = runBlocking {
        val api = FakeOandaApi(
            orderResponse = Response.success(
                OandaOrderCreateResponse(
                    orderFillTransaction = OandaOrderFillTransaction(
                        id = "tx-1", price = "1.08510", time = "2024-01-15T10:00:01.000000000Z",
                        tradeOpened = OandaTradeOpened(tradeID = "trade-42")
                    )
                )
            )
        )
        val adapter = OandaBrokerAdapter(api, "acc", OandaEnvironment.PRACTICE)
        val result = adapter.submitOrder(
            OrderRequest("client-1", CurrencyPair.EURUSD, TradeDirection.BUY, 0.1, 1.0800, 1.0950, EntryType.MARKET)
        )
        assertTrue(result is OrderResult.Filled)
        assertEquals("trade-42", (result as OrderResult.Filled).brokerOrderId)
        assertEquals(1.08510, result.filledPrice, 1e-9)
    }

    @Test
    fun submitOrderMapsCancelTransactionToRejected() = runBlocking {
        val api = FakeOandaApi(
            orderResponse = Response.success(OandaOrderCreateResponse(orderCancelTransaction = OandaOrderCancelTransaction(reason = "MARKET_HALTED")))
        )
        val adapter = OandaBrokerAdapter(api, "acc", OandaEnvironment.PRACTICE)
        val result = adapter.submitOrder(
            OrderRequest("client-2", CurrencyPair.EURUSD, TradeDirection.SELL, 0.1, 1.0950, 1.0800, EntryType.MARKET)
        )
        assertTrue(result is OrderResult.Rejected)
        assertEquals("MARKET_HALTED", (result as OrderResult.Rejected).reason)
    }

    @Test
    fun submitOrderReturnsUnknownRatherThanRejectedOnNetworkException() = runBlocking {
        val throwingApi = object : OandaApi by FakeOandaApi() {
            override suspend fun createOrder(accountId: String, request: OandaCreateOrderRequest): Response<OandaOrderCreateResponse> {
                throw java.io.IOException("timeout")
            }
        }
        val adapter = OandaBrokerAdapter(throwingApi, "acc", OandaEnvironment.PRACTICE)
        val result = adapter.submitOrder(
            OrderRequest("client-3", CurrencyPair.EURUSD, TradeDirection.BUY, 0.1, 1.0800, 1.0950, EntryType.MARKET)
        )
        // Critical safety property (spec §15): ambiguous failure must never be reported as a
        // clean Rejected, which would invite a blind retry and a possible duplicate live order.
        assertTrue(result is OrderResult.Unknown)
    }

    @Test
    fun practiceEnvironmentMapsToDemoModeLiveMapsToLive() {
        val practice = OandaBrokerAdapter(FakeOandaApi(), "acc", OandaEnvironment.PRACTICE)
        val live = OandaBrokerAdapter(FakeOandaApi(), "acc", OandaEnvironment.LIVE)
        assertEquals(TradingMode.DEMO, practice.mode)
        assertEquals(TradingMode.LIVE, live.mode)
    }
}
