package com.jarvis.assistant.quotex.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jarvis.assistant.quotex.domain.Candle

/** A closed candle built from on-screen price readings. Unique per asset and open time. */
@Entity(tableName = "quotex_candles", indices = [Index(value = ["asset", "openTimeMs"], unique = true)])
data class QuotexCandleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val asset: String,
    val openTimeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

/**
 * One logged signal, saved the moment it is surfaced and updated once its result is known (section 27).
 * [reasonSummary] is frozen at entry time so a later "why did this fail?" quotes what JARVIS actually said
 * then, not a reconstruction after the fact.
 */
@Entity(tableName = "quotex_journal")
data class QuotexJournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val asset: String,
    val candleSeconds: Int,
    val expiryCandles: Int,
    val direction: String,
    val confidence: Double,
    val agree: Int,
    val totalModels: Int,
    val trend: String,
    val volatility: String,
    val confluenceQuality: String,
    val signalState: String,
    val entryPrice: Double,
    val reasonSummary: String,
    val resolvedAt: Long? = null,
    val actualDirection: String? = null,
    val correct: Boolean? = null
)

@Dao
interface QuotexCandleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(candle: QuotexCandleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(candles: List<QuotexCandleEntity>): List<Long>

    @Query("SELECT * FROM quotex_candles WHERE asset = :asset ORDER BY openTimeMs DESC LIMIT :limit")
    suspend fun latest(asset: String, limit: Int): List<QuotexCandleEntity>

    @Query("SELECT * FROM quotex_candles ORDER BY asset ASC, openTimeMs ASC")
    suspend fun all(): List<QuotexCandleEntity>

    @Query("SELECT COUNT(*) FROM quotex_candles WHERE asset = :asset")
    suspend fun count(asset: String): Int

    @Query("DELETE FROM quotex_candles")
    suspend fun clearAll()

    @Query("DELETE FROM quotex_candles WHERE asset = :asset")
    suspend fun clearAsset(asset: String)
}

@Dao
interface QuotexJournalDao {
    @Insert
    suspend fun insert(entry: QuotexJournalEntity): Long

    @Query("UPDATE quotex_journal SET resolvedAt = :resolvedAt, actualDirection = :actualDirection, correct = :correct WHERE id = :id")
    suspend fun resolve(id: Long, resolvedAt: Long, actualDirection: String, correct: Boolean)

    @Query("SELECT * FROM quotex_journal ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<QuotexJournalEntity>

    @Query("SELECT * FROM quotex_journal WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun since(sinceMs: Long): List<QuotexJournalEntity>

    @Query("SELECT * FROM quotex_journal WHERE correct = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastLoss(): QuotexJournalEntity?

    @Query("DELETE FROM quotex_journal")
    suspend fun clearAll()
}

/** Separate database file (quotex_db): neither JARVIS's own data nor the WinGo data is touched. */
@Database(entities = [QuotexCandleEntity::class, QuotexJournalEntity::class], version = 2, exportSchema = false)
abstract class QuotexDatabase : RoomDatabase() {
    abstract fun candleDao(): QuotexCandleDao
    abstract fun journalDao(): QuotexJournalDao

    companion object {
        /** Adds the trade journal table (section 27). Existing candle history is kept exactly as it was. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quotex_journal (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, asset TEXT NOT NULL, " +
                        "candleSeconds INTEGER NOT NULL, expiryCandles INTEGER NOT NULL, direction TEXT NOT NULL, " +
                        "confidence REAL NOT NULL, agree INTEGER NOT NULL, totalModels INTEGER NOT NULL, trend TEXT NOT NULL, " +
                        "volatility TEXT NOT NULL, confluenceQuality TEXT NOT NULL, signalState TEXT NOT NULL, " +
                        "entryPrice REAL NOT NULL, reasonSummary TEXT NOT NULL, resolvedAt INTEGER, actualDirection TEXT, correct INTEGER)"
                )
            }
        }

        fun create(context: Context): QuotexDatabase =
            Room.databaseBuilder(context.applicationContext, QuotexDatabase::class.java, "quotex_db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

private fun Candle.toEntity(asset: String) = QuotexCandleEntity(asset = asset, openTimeMs = openTimeMs, open = open, high = high, low = low, close = close)
private fun QuotexCandleEntity.toDomain() = Candle(openTimeMs, open, high, low, close)

class QuotexCandleRepository(private val dao: QuotexCandleDao) {
    suspend fun clearAsset(asset: String) = dao.clearAsset(asset)

    suspend fun insert(asset: String, candle: Candle): Boolean = dao.insert(candle.toEntity(asset)) != -1L

    /** The most recent [limit] candles of [asset], oldest first. */
    suspend fun latestAscending(asset: String, limit: Int): List<Candle> =
        dao.latest(asset, limit).map { it.toDomain() }.reversed()

    suspend fun allByAsset(): Map<String, List<Candle>> =
        dao.all().groupBy({ it.asset }, { it.toDomain() })

    /** Bulk restore; existing (asset, time) pairs are skipped. Returns how many were new. */
    suspend fun insertAll(byAsset: Map<String, List<Candle>>): Int {
        var added = 0
        for ((asset, candles) in byAsset) {
            added += dao.insertAll(candles.map { it.toEntity(asset) }).count { it != -1L }
        }
        return added
    }

    suspend fun count(asset: String): Int = dao.count(asset)

    suspend fun clear() = dao.clearAll()
}

/** Domain-level view of one journal row, independent of the Room entity shape. */
data class JournalEntry(
    val id: Long,
    val timestamp: Long,
    val asset: String,
    val candleSeconds: Int,
    val expiryCandles: Int,
    val direction: String,
    val confidence: Double,
    val agree: Int,
    val totalModels: Int,
    val trend: String,
    val volatility: String,
    val confluenceQuality: String,
    val signalState: String,
    val entryPrice: Double,
    val reasonSummary: String,
    val resolvedAt: Long?,
    val actualDirection: String?,
    val correct: Boolean?
) {
    val resolved: Boolean get() = correct != null
}

private fun QuotexJournalEntity.toDomain() = JournalEntry(
    id, timestamp, asset, candleSeconds, expiryCandles, direction, confidence, agree, totalModels,
    trend, volatility, confluenceQuality, signalState, entryPrice, reasonSummary, resolvedAt, actualDirection, correct
)

class QuotexJournalRepository(private val dao: QuotexJournalDao) {
    suspend fun insert(entry: QuotexJournalEntity): Long = dao.insert(entry)

    suspend fun resolve(id: Long, resolvedAt: Long, actualDirection: String, correct: Boolean) =
        dao.resolve(id, resolvedAt, actualDirection, correct)

    suspend fun latest(limit: Int): List<JournalEntry> = dao.latest(limit).map { it.toDomain() }

    suspend fun since(sinceMs: Long): List<JournalEntry> = dao.since(sinceMs).map { it.toDomain() }

    suspend fun lastLoss(): JournalEntry? = dao.lastLoss()?.toDomain()

    suspend fun clear() = dao.clearAll()
}
