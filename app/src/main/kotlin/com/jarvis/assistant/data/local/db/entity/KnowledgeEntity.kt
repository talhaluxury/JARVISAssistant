package com.jarvis.assistant.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * #45 KNOWLEDGE BASE
 *
 * General, non-personal facts JARVIS can draw on — how its own permissions/features work,
 * common Android behavior, and troubleshooting guidance — as distinct from [MemoryEntity],
 * which is only ever about the specific user. [externalId] is the stable id from the bundled
 * JSON (see the JSON files under app/src/main/assets/knowledge) so re-seeding on app update replaces a
 * changed entry instead of duplicating it. [source] distinguishes bundled content from any
 * future user- or admin-supplied entries without mixing the two.
 */
@Entity(tableName = "knowledge_entries", indices = [Index(value = ["externalId"], unique = true)])
data class KnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalId: String,
    val category: String, // android, jarvis, device, automation, troubleshooting, voice, system, app_capabilities
    val topic: String,
    val content: String,
    val keywords: String, // space-joined, used for relevance retrieval
    val source: String = "bundled" // bundled | custom
)
