package com.jarvis.assistant.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One CLOSED demo (paper) trade. The queryable columns are stored flat; [json] holds the complete trade (reasons, indicators,
 * strategies, explanation ...) so nothing is lost and old rows stay readable when the trade model gains fields.
 * DEMO / PAPER ONLY: these rows describe simulated trades, never real orders.
 */
@Entity(tableName = "demo_trades", indices = [Index(value = ["closedAtMs"])])
data class DemoTradeEntity(
    @PrimaryKey val id: Int,
    val asset: String,
    val direction: String,
    val result: String,
    val pnl: Double,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val json: String
)
