package com.jarvis.assistant.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.data.local.db.entity.DemoTradeEntity

/** Plain (blocking) DAO: it is only ever called from the single background thread of RoomDemoTradeHistory. */
@Dao
interface DemoTradeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(trade: DemoTradeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(trades: List<DemoTradeEntity>)

    @Query("SELECT * FROM demo_trades ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): List<DemoTradeEntity>

    @Query("SELECT COUNT(*) FROM demo_trades")
    fun count(): Int

    @Query("DELETE FROM demo_trades")
    fun clear()

    @Query("DELETE FROM demo_trades WHERE id NOT IN (SELECT id FROM demo_trades ORDER BY id DESC LIMIT :keep)")
    fun trim(keep: Int)
}
