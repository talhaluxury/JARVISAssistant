package com.jarvis.assistant.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.assistant.data.local.db.dao.CommandHistoryDao
import com.jarvis.assistant.data.local.db.dao.ConversationDao
import com.jarvis.assistant.data.local.db.dao.KnowledgeDao
import com.jarvis.assistant.data.local.db.dao.MemoryDao
import com.jarvis.assistant.data.local.db.dao.MessageDao
import com.jarvis.assistant.data.local.db.dao.PreferenceDao
import com.jarvis.assistant.data.local.db.dao.SystemEventDao
import com.jarvis.assistant.data.local.db.dao.TaskOutcomeDao
import com.jarvis.assistant.data.local.db.entity.CommandHistoryEntity
import com.jarvis.assistant.data.local.db.entity.ConversationEntity
import com.jarvis.assistant.data.local.db.entity.KnowledgeEntity
import com.jarvis.assistant.data.local.db.entity.MemoryEntity
import com.jarvis.assistant.data.local.db.entity.MessageEntity
import com.jarvis.assistant.data.local.db.entity.PreferenceEntity
import com.jarvis.assistant.data.local.db.entity.SystemEventEntity
import com.jarvis.assistant.data.local.db.entity.TaskOutcomeEntity

@Database(
    entities = [
        MemoryEntity::class, ConversationEntity::class, MessageEntity::class,
        CommandHistoryEntity::class, TaskOutcomeEntity::class, SystemEventEntity::class, PreferenceEntity::class,
        KnowledgeEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun taskOutcomeDao(): TaskOutcomeDao
    abstract fun systemEventDao(): SystemEventDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        @Volatile private var INSTANCE: JarvisDatabase? = null

        /**
         * #38 ADVANCED MEMORY ARCHITECTURE — v1 -> v2 adds the metadata columns to `memories`
         * plus the four new layers (command history, task outcomes, system events,
         * preferences). Written as real, explicit SQL rather than destructive fallback so
         * nobody's existing remembered facts are ever silently wiped by an app update.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN category TEXT NOT NULL DEFAULT 'general'")
                db.execSQL("ALTER TABLE memories ADD COLUMN source TEXT NOT NULL DEFAULT 'user_explicit'")
                db.execSQL("ALTER TABLE memories ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE memories ADD COLUMN approved INTEGER NOT NULL DEFAULT 1")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `command_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `commandType` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `success` INTEGER NOT NULL,
                        `resultMessage` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `task_outcomes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userRequest` TEXT NOT NULL,
                        `intentCategory` TEXT NOT NULL,
                        `stepCount` INTEGER NOT NULL,
                        `success` INTEGER NOT NULL,
                        `failureReason` TEXT,
                        `executionTimeMs` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `system_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `preferences_memory` (
                        `key` TEXT PRIMARY KEY NOT NULL,
                        `value` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
        }

        /**
         * #45 KNOWLEDGE BASE — v2 -> v3 adds the knowledge_entries table. Bundled content is
         * seeded from the JSON files under app/src/main/assets/knowledge at app startup (see
         * JarvisApplication.onCreate / KnowledgeRepository.ensureSeeded), not by the migration
         * itself — the migration only needs to create the table shape.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `knowledge_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `externalId` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `keywords` TEXT NOT NULL,
                        `source` TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_knowledge_entries_externalId` ON `knowledge_entries` (`externalId`)")
            }
        }

        fun getInstance(context: Context): JarvisDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
