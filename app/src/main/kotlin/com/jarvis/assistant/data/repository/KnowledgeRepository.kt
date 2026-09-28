package com.jarvis.assistant.data.repository

import android.content.Context
import com.jarvis.assistant.data.local.db.dao.KnowledgeDao
import com.jarvis.assistant.data.local.db.entity.KnowledgeEntity
import org.json.JSONArray

/**
 * #45 KNOWLEDGE BASE — loads the bundled, category-separated JSON files under
 * app/src/main/assets/knowledge/ into the local database once, then serves everything from
 * there. Keeping the content in data files (not Kotlin code) means editing or adding facts
 * never touches application logic, and a future update mechanism (e.g. dropping a refreshed
 * JSON file and re-seeding) would not require rebuilding the agent/prompt code at all.
 */
class KnowledgeRepository(private val context: Context, private val dao: KnowledgeDao) {

    private val categories = listOf(
        "android", "jarvis", "device", "automation", "troubleshooting", "voice", "system", "app_capabilities"
    )

    /** No-op once bundled rows already exist — safe to call on every app start. */
    suspend fun ensureSeeded() {
        if (dao.bundledCount() > 0) return
        val entries = categories.flatMap { loadCategoryAsset(it) }
        if (entries.isNotEmpty()) dao.upsertAll(entries)
    }

    suspend fun all(): List<KnowledgeEntity> = dao.getAll()
    suspend fun byCategory(category: String): List<KnowledgeEntity> = dao.byCategory(category)
    suspend fun totalCount(): Int = dao.totalCount()

    /** Never fabricates content — a missing or malformed asset file simply contributes no
     * entries for that category rather than being papered over with placeholder facts. */
    private fun loadCategoryAsset(category: String): List<KnowledgeEntity> = runCatching {
        val json = context.assets.open("knowledge/$category.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val keywordsArray = obj.optJSONArray("keywords")
            val keywords = buildList {
                if (keywordsArray != null) for (k in 0 until keywordsArray.length()) add(keywordsArray.getString(k))
            }
            KnowledgeEntity(
                externalId = obj.getString("id"),
                category = category,
                topic = obj.getString("topic"),
                content = obj.getString("content"),
                keywords = keywords.joinToString(" "),
                source = "bundled"
            )
        }
    }.getOrElse { emptyList() }
}
