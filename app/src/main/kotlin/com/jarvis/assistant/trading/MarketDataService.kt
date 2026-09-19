package com.jarvis.assistant.trading

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * PHASE 1 — BROKER ABSTRACTION + MARKET DATA
 *
 * spec §13: "The app must not be tied permanently to one broker." Every later engine (structure,
 * signal, risk, execution) talks to [BrokerAdapter] / [MarketDataService], never to a concrete
 * broker SDK. Adding OANDA/IG/MT4-bridge/etc. later means implementing this one interface —
 * nothing else in the trading package changes.
 *
 * No LiveBrokerAdapter is implemented yet: that requires picking a real broker and its
 * credential flow (spec §31 — secrets never hardcoded, Android Keystore / secure backend only),
 * which is a deliberate later phase, not a Phase 1 concern.
 */

data class AccountSnapshot(
    val accountId: String,
    val balance: Double,
    val equity: Double,
    val marginUsed: Double,
    val marginAvailable: Double,
    val currency: String
)

data class OrderRequest(
    val clientOrderId: String, // caller-generated idempotency key — see DuplicateOrderGuard (later phase)
    val pair: CurrencyPair,
    val direction: TradeDirection,
    val lotSize: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val entryType: EntryType,
    val limitOrStopPrice: Double? = null
)

enum class EntryType { MARKET, LIMIT, STOP }

/** Never a bare boolean — spec §13/§15: "always verify broker response, never assume an order
 * succeeded" and "determine whether the order actually executed" on failure. Every branch here
 * is something the execution engine (later phase) must handle differently. */
sealed class OrderResult {
    data class Filled(val brokerOrderId: String, val filledPrice: Double, val filledAtEpochMillis: Long) : OrderResult()
    data class Rejected(val reason: String) : OrderResult()
    /** Submitted but broker has not confirmed fill/rejection yet — must NOT be reported to the
     * user as success (spec §25: "never tell the user a trade succeeded without confirmation"). */
    data class Pending(val brokerOrderId: String) : OrderResult()
    data class Unknown(val reason: String) : OrderResult() // e.g. network dropped after submit
}

interface BrokerAdapter {
    val mode: TradingMode
    suspend fun connect(): Boolean
    suspend fun isConnected(): Boolean
    suspend fun account(): AccountSnapshot?
    suspend fun latestTick(pair: CurrencyPair): PriceTick?
    suspend fun candles(pair: CurrencyPair, timeframe: Timeframe, count: Int): List<Candle>
    suspend fun openPositions(): List<OrderRequest>
    suspend fun submitOrder(request: OrderRequest): OrderResult
    suspend fun closePosition(brokerOrderId: String): OrderResult
}

/**
 * Deterministic-enough, clearly-labeled simulated feed for paper trading and development.
 * Every tick/candle it produces is tagged [DataSource.DEMO_SIMULATED] — nothing downstream can
 * mistake this for real market data, and the HUD (later phase) must surface that tag prominently
 * per spec §24 ("HUD must clearly distinguish DEMO from LIVE").
 *
 * The walk uses a small random-walk-with-drift model per pair, seeded per pair so repeated runs
 * in tests are reproducible. This is NOT a market simulator meant to resemble real price
 * dynamics for backtesting purposes (that's the dedicated BacktestEngine in a much later phase);
 * it exists only so the rest of the pipeline has something realistic-shaped to run against.
 */
class DemoBrokerAdapter(
    startingBalance: Double = 10_000.0
) : BrokerAdapter {

    override val mode = TradingMode.DEMO

    private var connected = false
    private var balance = startingBalance
    private val openOrders = mutableMapOf<String, OrderRequest>()
    private val random = Random(seed = 42)

    // Rough, illustrative starting mid-prices — not sourced from any live feed.
    private val basePrice = mutableMapOf(
        CurrencyPair.EURUSD to 1.0850,
        CurrencyPair.GBPUSD to 1.2650,
        CurrencyPair.USDJPY to 149.50,
        CurrencyPair.USDCHF to 0.8800,
        CurrencyPair.AUDUSD to 0.6550,
        CurrencyPair.USDCAD to 1.3650,
        CurrencyPair.NZDUSD to 0.6050,
        CurrencyPair.XAUUSD to 2350.0
    )

    override suspend fun connect(): Boolean {
        connected = true
        return true
    }

    override suspend fun isConnected(): Boolean = connected

    override suspend fun account(): AccountSnapshot? {
        if (!connected) return null
        val marginUsed = openOrders.values.sumOf { it.lotSize * 1000.0 }
        return AccountSnapshot(
            accountId = "DEMO-001",
            balance = balance,
            equity = balance, // simplified: unrealized P/L wiring lands with PositionMonitor (later phase)
            marginUsed = marginUsed,
            marginAvailable = (balance - marginUsed).coerceAtLeast(0.0),
            currency = "USD"
        )
    }

    override suspend fun latestTick(pair: CurrencyPair): PriceTick? {
        if (!connected) return null
        val mid = stepPrice(pair)
        val spread = pair.pipSize * 1.2 // ~1.2 pip synthetic spread
        return PriceTick(
            pair = pair,
            bid = mid - spread / 2,
            ask = mid + spread / 2,
            timestampEpochMillis = System.currentTimeMillis(),
            source = DataSource.DEMO_SIMULATED
        )
    }

    override suspend fun candles(pair: CurrencyPair, timeframe: Timeframe, count: Int): List<Candle> {
        if (!connected) return emptyList()
        val now = System.currentTimeMillis()
        val periodMillis = timeframe.minutes * 60_000L
        var price = basePrice.getValue(pair)
        val candles = ArrayList<Candle>(count)
        // Walk backwards from "now" so the most recent candle's close matches stepPrice()'s
        // current level, then present in chronological order.
        for (i in count downTo 1) {
            val openTime = now - i * periodMillis
            val drift = driftFor(pair)
            val vol = volatilityFor(pair) * sqrt(timeframe.minutes.toDouble())
            val open = price
            val close = (open + drift + random.nextGaussian() * vol).coerceAtLeast(pair.pipSize)
            val high = maxOf(open, close) + kotlin.math.abs(random.nextGaussian() * vol * 0.4)
            val low = minOf(open, close) - kotlin.math.abs(random.nextGaussian() * vol * 0.4)
            candles += Candle(
                pair = pair,
                timeframe = timeframe,
                openTimeEpochMillis = openTime,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = null,
                source = DataSource.DEMO_SIMULATED
            )
            price = close
        }
        basePrice[pair] = price
        return candles
    }

    override suspend fun openPositions(): List<OrderRequest> = openOrders.values.toList()

    override suspend fun submitOrder(request: OrderRequest): OrderResult {
        if (!connected) return OrderResult.Rejected("Not connected to demo broker.")
        if (openOrders.containsKey(request.clientOrderId)) {
            return OrderResult.Rejected("Duplicate clientOrderId: ${request.clientOrderId}")
        }
        val tick = latestTick(request.pair) ?: return OrderResult.Rejected("No price available for ${request.pair}")
        val fillPrice = if (request.direction == TradeDirection.BUY) tick.ask else tick.bid
        openOrders[request.clientOrderId] = request
        return OrderResult.Filled(
            brokerOrderId = request.clientOrderId,
            filledPrice = fillPrice,
            filledAtEpochMillis = System.currentTimeMillis()
        )
    }

    override suspend fun closePosition(brokerOrderId: String): OrderResult {
        val removed = openOrders.remove(brokerOrderId)
            ?: return OrderResult.Rejected("No open demo position with id $brokerOrderId")
        return OrderResult.Filled(brokerOrderId, basePrice.getValue(removed.pair), System.currentTimeMillis())
    }

    private fun stepPrice(pair: CurrencyPair): Double {
        val current = basePrice.getValue(pair)
        val next = (current + driftFor(pair) + random.nextGaussian() * volatilityFor(pair))
            .coerceAtLeast(pair.pipSize)
        basePrice[pair] = next
        return next
    }

    private fun driftFor(pair: CurrencyPair): Double = 0.0 // no systematic drift — pure random walk
    private fun volatilityFor(pair: CurrencyPair): Double = when (pair) {
        CurrencyPair.XAUUSD -> 0.35
        CurrencyPair.USDJPY -> 0.03
        else -> pair.pipSize * 3
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller transform — kotlin.random has no built-in Gaussian sampler.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}

/**
 * Wraps a [BrokerAdapter] with the staleness/validity checks spec §20/§21 require of every
 * consumer, so no analysis engine has to remember to re-check freshness itself.
 */
class MarketDataService(
    private val adapter: BrokerAdapter,
    private val staleThresholdSeconds: Int = 30
) {
    fun tickStream(pair: CurrencyPair, pollIntervalMillis: Long = 1000L): Flow<PriceTick?> = flow {
        while (true) {
            emit(adapter.latestTick(pair))
            kotlinx.coroutines.delay(pollIntervalMillis)
        }
    }

    suspend fun validatedTick(pair: CurrencyPair, nowEpochMillis: Long = System.currentTimeMillis()): DataQuality {
        val tick = adapter.latestTick(pair) ?: return DataQuality.Invalid("No price available for $pair.")
        return validate(tick, nowEpochMillis)
    }

    fun validate(tick: PriceTick, nowEpochMillis: Long = System.currentTimeMillis()): DataQuality {
        val ageSeconds = (nowEpochMillis - tick.timestampEpochMillis) / 1000.0
        return when {
            tick.bid <= 0 || tick.ask <= 0 -> DataQuality.Invalid("Non-positive bid/ask for ${tick.pair}.")
            tick.ask < tick.bid -> DataQuality.Invalid("Crossed spread (ask < bid) for ${tick.pair}.")
            ageSeconds > staleThresholdSeconds ->
                DataQuality.Invalid("Stale data for ${tick.pair}: ${ageSeconds.toInt()}s old (limit ${staleThresholdSeconds}s).")
            else -> DataQuality.Valid
        }
    }

    suspend fun candles(pair: CurrencyPair, timeframe: Timeframe, count: Int): List<Candle> =
        adapter.candles(pair, timeframe, count)
}
