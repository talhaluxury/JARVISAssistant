package com.jarvis.assistant.trading.oanda

import com.jarvis.assistant.trading.*
import retrofit2.Response
import java.time.Instant

/**
 * PHASE 9 — OANDA BROKER ADAPTER
 *
 * The first [BrokerAdapter] backed by a real broker, per spec §13's "the app must not be tied
 * permanently to one broker" (this is one implementation of that interface, not a replacement
 * for [DemoBrokerAdapter]). Selecting [OandaEnvironment.PRACTICE] gives [mode] = DEMO — meaning
 * this adapter can be dropped straight into [PaperTradingEngine] to paper-trade against REAL
 * market data instead of the synthetic random walk, which is strictly more useful for validating
 * a strategy. [OandaEnvironment.LIVE] gives [mode] = LIVE, which [PaperTradingEngine] will
 * refuse to accept (by design) — live execution needs its own, more heavily gated engine that
 * isn't built yet; see the comment block at the bottom of this file for exactly what that still
 * requires before it should exist.
 *
 * UNTESTED AGAINST A REAL ACCOUNT: this sandbox has no network access and no OANDA API key.
 * Every DTO in OandaApi.kt and every mapping below reflects the public v20 API docs as best
 * modeled here, but has not been run against a live sandbox. Two assumptions in particular need
 * verification against real responses before this is trusted with even a practice account:
 *  1. That `bids[0]`/`asks[0]` in a pricing response are the BEST (top-of-book) prices, not the
 *     worst — this determines which element index [latestTick] reads.
 *  2. The exact field names for a trade's attached stop-loss/take-profit orders (modeled here as
 *     `stopLossOrder`/`takeProfitOrder` with a `price` field) — if OANDA's real shape differs,
 *     [openPositions] will silently report 0.0 for those fields rather than crash, which is safe
 *     but means the values would be wrong until this is corrected against real data.
 * Run this against your own OANDA practice account and diff the raw JSON against OandaApi.kt
 * before relying on it for anything beyond that verification.
 */
enum class OandaEnvironment(val baseUrl: String, val mode: TradingMode) {
    PRACTICE("https://api-fxpractice.oanda.com/", TradingMode.DEMO),
    LIVE("https://api-fxtrade.oanda.com/", TradingMode.LIVE)
}

class OandaBrokerAdapter(
    private val api: OandaApi,
    private val accountId: String,
    environment: OandaEnvironment
) : BrokerAdapter {

    override val mode: TradingMode = environment.mode

    @Volatile private var connected = false

    override suspend fun connect(): Boolean {
        connected = runCatching { api.accountSummary(accountId).isSuccessful }.getOrDefault(false)
        return connected
    }

    override suspend fun isConnected(): Boolean = connected

    override suspend fun account(): AccountSnapshot? = runCatching {
        val body = api.accountSummary(accountId).bodyOrNull() ?: return@runCatching null
        val a = body.account
        AccountSnapshot(
            accountId = a.id,
            balance = a.balance.toDouble(),
            equity = a.nav.toDouble(),
            marginUsed = a.marginUsed.toDouble(),
            marginAvailable = a.marginAvailable.toDouble(),
            currency = a.currency
        )
    }.getOrNull()

    override suspend fun latestTick(pair: CurrencyPair): PriceTick? = runCatching {
        val instrument = instrumentCode(pair)
        val body = api.pricing(accountId, instrument).bodyOrNull() ?: return@runCatching null
        val price = body.prices.firstOrNull { it.instrument == instrument } ?: return@runCatching null
        val bid = price.bids.firstOrNull()?.price?.toDouble() ?: return@runCatching null
        val ask = price.asks.firstOrNull()?.price?.toDouble() ?: return@runCatching null
        PriceTick(pair, bid, ask, parseTime(price.time), DataSource.BROKER_LIVE)
    }.getOrNull()

    override suspend fun candles(pair: CurrencyPair, timeframe: Timeframe, count: Int): List<Candle> = runCatching {
        val instrument = instrumentCode(pair)
        val body = api.candles(instrument, granularityCode(timeframe), count).bodyOrNull() ?: return@runCatching emptyList()
        body.candles
            .filter { it.complete && it.mid != null }
            .map { c ->
                val mid = c.mid!!
                Candle(
                    pair = pair, timeframe = timeframe, openTimeEpochMillis = parseTime(c.time),
                    open = mid.o.toDouble(), high = mid.h.toDouble(), low = mid.l.toDouble(), close = mid.c.toDouble(),
                    volume = c.volume.toDouble(), source = DataSource.BROKER_HISTORICAL
                )
            }
    }.getOrDefault(emptyList())

    override suspend fun openPositions(): List<OrderRequest> = runCatching {
        val body = api.openTrades(accountId).bodyOrNull() ?: return@runCatching emptyList()
        body.trades.mapNotNull { trade ->
            val pair = pairFromInstrument(trade.instrument) ?: return@mapNotNull null
            val units = trade.currentUnits.toDoubleOrNull() ?: return@mapNotNull null
            OrderRequest(
                clientOrderId = trade.id,
                pair = pair,
                direction = if (units >= 0) TradeDirection.BUY else TradeDirection.SELL,
                lotSize = kotlin.math.abs(units) / STANDARD_LOT_UNITS,
                // See class doc point 2 — these attached-order fields are a best-effort guess at
                // OANDA's real trade shape and default to 0.0 (never a crash) if absent/wrong.
                stopLoss = 0.0,
                takeProfit = 0.0,
                entryType = EntryType.MARKET
            )
        }
    }.getOrDefault(emptyList())

    override suspend fun submitOrder(request: OrderRequest): OrderResult {
        val instrument = instrumentCode(request.pair)
        val signedUnits = (request.lotSize * STANDARD_LOT_UNITS).let {
            if (request.direction == TradeDirection.BUY) it else -it
        }.toLong()
        val payload = OandaCreateOrderRequest(
            OandaOrderPayload(
                type = when (request.entryType) { EntryType.MARKET -> "MARKET"; EntryType.LIMIT -> "LIMIT"; EntryType.STOP -> "STOP" },
                instrument = instrument,
                units = signedUnits.toString(),
                price = request.limitOrStopPrice?.toString(),
                stopLossOnFill = OandaPriceSpec(formatPrice(request.stopLoss)),
                takeProfitOnFill = OandaPriceSpec(formatPrice(request.takeProfit)),
                // OANDA itself rejects a reused clientExtensions id — an extra layer of
                // duplicate-order protection on top of spec §14's, enforced broker-side.
                clientExtensions = OandaClientExtensions(request.clientOrderId)
            )
        )
        return runCatching {
            val response = api.createOrder(accountId, payload)
            val body = response.bodyOrNull()
            when {
                body?.orderFillTransaction != null -> {
                    val fill = body.orderFillTransaction
                    OrderResult.Filled(fill.tradeOpened?.tradeID ?: fill.id, fill.price.toDouble(), parseTime(fill.time))
                }
                body?.orderCancelTransaction != null -> OrderResult.Rejected(body.orderCancelTransaction.reason)
                body?.orderCreateTransaction != null -> OrderResult.Pending(body.orderCreateTransaction.id)
                response.isSuccessful -> OrderResult.Unknown("OANDA returned a successful but unrecognized response shape.")
                else -> OrderResult.Rejected("OANDA HTTP ${response.code()}: ${response.errorBody()?.string().orEmpty()}")
            }
        }.getOrElse { e ->
            // spec §15: a network failure after submission means we do NOT know whether the
            // order reached the broker — never report this as Rejected (which would invite a
            // blind retry and a possible duplicate fill).
            OrderResult.Unknown("Network error submitting order: ${e.message}")
        }
    }

    override suspend fun closePosition(brokerOrderId: String): OrderResult = runCatching {
        val response = api.closeTrade(accountId, brokerOrderId)
        val fill = response.bodyOrNull()?.orderFillTransaction
        if (fill != null) OrderResult.Filled(fill.id, fill.price.toDouble(), parseTime(fill.time))
        else if (response.isSuccessful) OrderResult.Unknown("Close request accepted but no fill transaction returned.")
        else OrderResult.Rejected("OANDA HTTP ${response.code()} closing $brokerOrderId.")
    }.getOrElse { e -> OrderResult.Unknown("Network error closing position: ${e.message}") }

    companion object {
        const val STANDARD_LOT_UNITS = 100_000.0

        fun instrumentCode(pair: CurrencyPair): String = "${pair.base}_${pair.quote}"

        fun pairFromInstrument(instrument: String): CurrencyPair? {
            val parts = instrument.split("_")
            if (parts.size != 2) return null
            return CurrencyPair.entries.firstOrNull { it.base == parts[0] && it.quote == parts[1] }
        }

        fun granularityCode(timeframe: Timeframe): String = when (timeframe) {
            Timeframe.M1 -> "M1"; Timeframe.M5 -> "M5"; Timeframe.M15 -> "M15"
            Timeframe.H1 -> "H1"; Timeframe.H4 -> "H4"; Timeframe.D1 -> "D"
        }

        fun parseTime(iso: String): Long = Instant.parse(iso).toEpochMilli()

        fun formatPrice(price: Double): String = String.format(java.util.Locale.ROOT, "%.5f", price)

        private fun <T> Response<T>.bodyOrNull(): T? = if (isSuccessful) body() else null
    }
}

/*
 * WHAT'S STILL MISSING BEFORE LIVE (real-money) EXECUTION THROUGH THIS ADAPTER IS SAFE TO BUILD:
 * spec §36 requires, at minimum, all of the following, none of which exist yet:
 *  - A LiveTradingEngine distinct from PaperTradingEngine, since PaperTradingEngine's autonomous
 *    scan()/evaluate() path is deliberately locked to TradingMode.DEMO and must stay that way.
 *  - Re-validation of price/spread/margin IMMEDIATELY before submission (not just at signal time
 *    a few seconds earlier) — a live fill uses real money if the market moved in between.
 *  - Idempotent retry/reconciliation logic that queries openTrades/order history to determine
 *    what actually happened after a genuinely ambiguous (timeout/5xx) response, rather than
 *    just returning Unknown and stopping (correct as a MINIMUM per spec §15, but a real live
 *    system should also attempt reconciliation on the next tick).
 *  - The explicit per-session and per-trade voice/UI confirmation flow spec §36 describes,
 *    which the existing ForexBrainCommandExecutor.requestManualTrade/confirmManualTrade flow
 *    was built for DEMO but would need a second, more heavily audited path for LIVE.
 * None of this is implemented here on purpose — it deserves its own careful phase with a real
 * account to test against, not to be rushed in alongside the adapter itself.
 */
