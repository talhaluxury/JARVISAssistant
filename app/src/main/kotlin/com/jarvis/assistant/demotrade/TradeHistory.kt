package com.jarvis.assistant.demotrade

import com.jarvis.assistant.data.local.db.dao.DemoTradeDao
import com.jarvis.assistant.data.local.db.entity.DemoTradeEntity
import kotlinx.serialization.json.Json
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Where CLOSED demo trades live for good. The engine only talks to this interface, so tests use [InMemoryTradeHistory] and
 * the app uses [RoomTradeHistory] (the app's Room database, table `demo_trades`).
 */
interface DemoTradeHistoryStore {
    /** Newest [limit] closed trades, oldest first. Never throws; returns an empty list on any storage problem. */
    fun loadRecent(limit: Int): List<PaperTrade>
    fun upsert(trade: PaperTrade)
    fun upsertAll(trades: List<PaperTrade>)
    fun clear()
}

class InMemoryTradeHistory : DemoTradeHistoryStore {
    private val rows = LinkedHashMap<Int, PaperTrade>()

    @Synchronized override fun loadRecent(limit: Int): List<PaperTrade> = rows.values.sortedBy { it.id }.takeLast(limit)
    @Synchronized override fun upsert(trade: PaperTrade) { rows[trade.id] = trade }
    @Synchronized override fun upsertAll(trades: List<PaperTrade>) { trades.forEach { rows[it.id] = it } }
    @Synchronized override fun clear() { rows.clear() }
    @Synchronized fun size(): Int = rows.size
}

/** Pure conversion between a closed [PaperTrade] and its database row (unit-tested without Android). */
object TradeRowCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun toEntity(t: PaperTrade): DemoTradeEntity = DemoTradeEntity(
        id = t.id,
        asset = t.asset,
        direction = t.direction.name,
        result = t.result?.name ?: TradeResult.VOID.name,
        pnl = t.pnl ?: 0.0,
        openedAtMs = t.openedAtMs,
        closedAtMs = t.closedAtMs ?: t.expiresAtMs,
        json = json.encodeToString(PaperTrade.serializer(), t)
    )

    fun fromEntity(e: DemoTradeEntity): PaperTrade? = try {
        json.decodeFromString(PaperTrade.serializer(), e.json)
    } catch (ex: Exception) {
        null // one unreadable row must never hide the rest of the history
    }
}

/**
 * Room-backed history. Room refuses queries on the main thread and the UI calls the engine from the main thread, so every
 * database access runs on ONE background thread: writes are fire-and-forget (and stay in order), reads wait for the writes
 * queued before them.
 */
class RoomTradeHistory(private val dao: DemoTradeDao) : DemoTradeHistoryStore {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "demo-trade-db").apply { isDaemon = true } }

    override fun loadRecent(limit: Int): List<PaperTrade> = try {
        executor.submit(Callable { dao.recent(limit).mapNotNull { TradeRowCodec.fromEntity(it) } }).get().sortedBy { it.id }
    } catch (e: Exception) {
        emptyList()
    }

    override fun upsert(trade: PaperTrade) = async {
        dao.upsert(TradeRowCodec.toEntity(trade))
        dao.trim(MAX_ROWS)
    }

    override fun upsertAll(trades: List<PaperTrade>) = async {
        if (trades.isNotEmpty()) dao.upsertAll(trades.map { TradeRowCodec.toEntity(it) })
        dao.trim(MAX_ROWS)
    }

    override fun clear() = async { dao.clear() }

    private fun async(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    // history is best effort; the trading logic must never depend on it
                }
            }
        } catch (e: RejectedExecutionException) {
            // executor shut down: nothing to do
        }
    }

    companion object {
        /** The database keeps far more than the 500 trades the engine holds in memory. */
        const val MAX_ROWS = 5000
    }
}
