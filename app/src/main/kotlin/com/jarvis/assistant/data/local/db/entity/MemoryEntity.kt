package com.jarvis.assistant.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * #38 ADVANCED MEMORY ARCHITECTURE — user-approved long-term memory. This is the ONLY memory
 * layer the AI can add to, and only ever in response to an explicit "remember this" from the
 * user (see JarvisCommand.Remember) — so [approved] is always true for rows created today, but
 * the column exists so a future bulk-import or suggestion feature can never silently promote
 * unapproved content into memory the AI reads back as fact.
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: String = "general", // general, preference, fact, relationship, device
    val source: String = "user_explicit", // user_explicit is the only source in use today
    val confidence: Float = 1.0f, // 0..1 — reserved for future non-explicit sources
    val approved: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
