package com.jarvis.assistant.trading

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * PHASE 9 — OANDA v20 REST API SURFACE
 *
 * Written to the best of available knowledge of OANDA's documented v20 REST API
 * (developer.oanda.com/rest-live-v20). IMPORTANT: this was authored without the ability to make
 * a live network call against OANDA's practice environment to verify field names and response
 * shapes end-to-end — cross-check against their current docs and a real practice account before
 * trusting this with even paper-traded real orders, let alone live ones. OANDA's own JSON fields
 * are all strings (including numbers, to avoid floating-point precision loss on prices), which
 * is why every numeric field below is typed String and parsed with toDoubleOrNull() at the call
 * site rather than assumed to parse cleanly.
 */
interface OandaApi {
    @GET("v3/accounts/{accountId}/summary")
    suspend fun accountSummary(@Header("Authorization") auth: String, @Path("accountId") accountId: String): OandaAccountSummaryResponse

    @GET("v3/accounts/{accountId}/pricing")
    suspend fun pricing(
        @Header("Authorization") auth: String,
        @Path("accountId") accountId: String,
        @Query("instruments") instruments: String
    ): OandaPricingResponse

    @GET("v3/instruments/{instrument}/candles")
    suspend fun candles(
        @Header("Authorization") auth: String,
        @Path("instrument") instrument: String,
        @Query("granularity") granularity: String,
        @Query("count") count: Int,
        @Query("price") price: String = "M"
    ): OandaCandlesResponse

    @GET("v3/accounts/{accountId}/openTrades")
    suspend fun openTrades(@Header("Authorization") auth: String, @Path("accountId") accountId: String): OandaOpenTradesResponse

    @POST("v3/accounts/{accountId}/orders")
    suspend fun createOrder(
        @Header("Authorization") auth: String,
        @Path("accountId") accountId: String,
        @Body body: OandaOrderRequestBody
    ): Response<OandaOrderResponse>

    @PUT("v3/accounts/{accountId}/trades/{tradeSpecifier}/close")
    suspend fun closeTrade(
        @Header("Authorization") auth: String,
        @Path("accountId") accountId: String,
        @Path("tradeSpecifier") tradeSpecifier: String
    ): Response<OandaCloseTradeResponse>
}

data class OandaAccountSummaryResponse(val account: OandaAccount)
data class OandaAccount(val balance: String, val NAV: String, val marginUsed: String, val marginAvailable: String, val currency: String)

data class OandaPricingResponse(val prices: List<OandaPrice>)
data class OandaPrice(val instrument: String, val bids: List<OandaPriceLevel>?, val asks: List<OandaPriceLevel>?, val time: String)
data class OandaPriceLevel(val price: String)

data class OandaCandlesResponse(val candles: List<OandaCandle>)
data class OandaCandle(val time: String, val mid: OandaOhlc?, val complete: Boolean, val volume: Int)
data class OandaOhlc(val o: String, val h: String, val l: String, val c: String)

data class OandaOpenTradesResponse(val trades: List<OandaTrade>)
data class OandaTrade(val id: String, val instrument: String, val currentUnits: String, val price: String)

data class OandaOrderRequestBody(val order: OandaOrderPayload)
data class OandaOrderPayload(
    val units: String, // positive = buy, negative = sell — OANDA's own convention
    val instrument: String,
    val timeInForce: String = "FOK", // Fill-or-Kill: never leaves a partially-filled/resting order behind unexpectedly
    val type: String = "MARKET",
    val positionFill: String = "DEFAULT",
    val stopLossOnFill: OandaPriceSpec? = null,
    val takeProfitOnFill: OandaPriceSpec? = null,
    val clientExtensions: OandaClientExtensions? = null
)
data class OandaPriceSpec(val price: String)
data class OandaClientExtensions(val id: String)

data class OandaOrderResponse(
    val orderFillTransaction: OandaOrderFillTransaction? = null,
    val orderCancelTransaction: OandaOrderCancelTransaction? = null,
    val orderCreateTransaction: OandaOrderCreateTransaction? = null
)
data class OandaOrderFillTransaction(val id: String, val price: String, val tradeOpened: OandaTradeOpened? = null)
data class OandaTradeOpened(val tradeID: String)
data class OandaOrderCancelTransaction(val reason: String)
data class OandaOrderCreateTransaction(val id: String)

data class OandaCloseTradeResponse(val orderFillTransaction: OandaOrderFillTransaction? = null)
