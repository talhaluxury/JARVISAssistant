package com.jarvis.assistant.trading.oanda

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * PHASE 9 — OANDA v20 REST API CLIENT (spec §13's "first real BrokerAdapter")
 *
 * OANDA was chosen as the reference implementation because it publishes a full, free-to-use
 * practice (paper) environment against real market data with the exact same REST API as live —
 * this file works, unmodified, against either.
 *
 * IMPORTANT — this could not be tested against a real OANDA account: this sandbox has no network
 * access and no OANDA API key. Every field name and endpoint shape below reflects the public
 * v20 API documentation as of this codebase's training, but OANDA (like any external API) can
 * change field names or add required parameters over time. Before trusting this against a real
 * account — even a practice one — run [OandaBrokerAdapterIntegrationChecklist] (see the adapter
 * file) against your own practice account and compare the raw JSON to what's modeled here.
 */

// ---- Account ----

data class OandaAccountResponse(val account: OandaAccount)
data class OandaAccount(
    val id: String,
    val balance: String,
    @SerializedName("NAV") val nav: String, // net asset value == equity
    val marginUsed: String,
    val marginAvailable: String,
    val currency: String
)

// ---- Pricing ----

data class OandaPricingResponse(val prices: List<OandaPrice>)
data class OandaPrice(
    val instrument: String,
    val time: String,
    val bids: List<OandaPriceLevel>,
    val asks: List<OandaPriceLevel>,
    val tradeable: Boolean = true
)
data class OandaPriceLevel(val price: String, val liquidity: Long = 0L)

// ---- Candles ----

data class OandaCandlesResponse(val instrument: String, val granularity: String, val candles: List<OandaCandle>)
data class OandaCandle(
    val time: String,
    val volume: Long,
    val complete: Boolean,
    val mid: OandaOhlc? = null
)
data class OandaOhlc(val o: String, val h: String, val l: String, val c: String)

// ---- Orders ----

data class OandaCreateOrderRequest(val order: OandaOrderPayload)
data class OandaOrderPayload(
    val type: String, // "MARKET", "LIMIT", "STOP"
    val instrument: String,
    val units: String, // signed: positive = buy, negative = sell
    val price: String? = null, // required for LIMIT/STOP, omitted for MARKET
    val timeInForce: String = "FOK", // fill-or-kill for market orders
    val stopLossOnFill: OandaPriceSpec,
    val takeProfitOnFill: OandaPriceSpec,
    val clientExtensions: OandaClientExtensions? = null
)
data class OandaPriceSpec(val price: String)
data class OandaClientExtensions(val id: String)

data class OandaOrderCreateResponse(
    val orderFillTransaction: OandaOrderFillTransaction? = null,
    val orderCancelTransaction: OandaOrderCancelTransaction? = null,
    val orderCreateTransaction: OandaOrderCreateTransaction? = null
)
data class OandaOrderFillTransaction(
    val id: String,
    val price: String,
    val time: String,
    val tradeOpened: OandaTradeOpened? = null
)
data class OandaTradeOpened(val tradeID: String)
data class OandaOrderCancelTransaction(val reason: String)
data class OandaOrderCreateTransaction(val id: String)

// ---- Open trades / close ----

data class OandaOpenTradesResponse(val trades: List<OandaTrade>)
data class OandaTrade(
    val id: String,
    val instrument: String,
    val price: String,
    val currentUnits: String,
    val state: String
)

data class OandaCloseTradeResponse(val orderFillTransaction: OandaOrderFillTransaction? = null)

interface OandaApi {
    @GET("v3/accounts/{accountId}/summary")
    suspend fun accountSummary(@Path("accountId") accountId: String): Response<OandaAccountResponse>

    @GET("v3/accounts/{accountId}/pricing")
    suspend fun pricing(@Path("accountId") accountId: String, @Query("instruments") instruments: String): Response<OandaPricingResponse>

    @GET("v3/instruments/{instrument}/candles")
    suspend fun candles(
        @Path("instrument") instrument: String,
        @Query("granularity") granularity: String,
        @Query("count") count: Int,
        @Query("price") price: String = "M" // mid prices
    ): Response<OandaCandlesResponse>

    @retrofit2.http.POST("v3/accounts/{accountId}/orders")
    suspend fun createOrder(@Path("accountId") accountId: String, @Body request: OandaCreateOrderRequest): Response<OandaOrderCreateResponse>

    @GET("v3/accounts/{accountId}/openTrades")
    suspend fun openTrades(@Path("accountId") accountId: String): Response<OandaOpenTradesResponse>

    @PUT("v3/accounts/{accountId}/trades/{tradeSpecifier}/close")
    suspend fun closeTrade(@Path("accountId") accountId: String, @Path("tradeSpecifier") tradeSpecifier: String): Response<OandaCloseTradeResponse>
}
