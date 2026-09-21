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
}

/** Separate database file (quotex_db): neither JARVIS's own data nor the WinGo data is touched. */
@Database(entities = [QuotexCandleEntity::class], version = 1, exportSchema = false)
abstract class QuotexDatabase : RoomDatabase() {
    abstract fun candleDao(): QuotexCandleDao

    companion object {
        fun create(context: Context): QuotexDatabase =
            Room.databaseBuilder(context.applicationContext, QuotexDatabase::class.java, "quotex_db").build()
    }
}

private fun Candle.toEntity(asset: String) = QuotexCandleEntity(asset = asset, openTimeMs = openTimeMs, open = open, high = high, low = low, close = close)
private fun QuotexCandleEntity.toDomain() = Candle(openTimeMs, open, high, low, close)

class QuotexCandleRepository(private val dao: QuotexCandleDao) {
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
