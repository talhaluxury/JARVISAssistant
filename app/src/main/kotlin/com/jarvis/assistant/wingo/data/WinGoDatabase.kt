package com.jarvis.assistant.wingo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Deliberately a SEPARATE database file from JarvisDatabase: the existing memory/brain schema and its
 * migrations stay untouched, so adding this module cannot corrupt or wipe anything JARVIS already has.
 */
@Database(
    entities = [GameResultEntity::class, PredictionRecordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class WinGoDatabase : RoomDatabase() {
    abstract fun gameResultDao(): GameResultDao
    abstract fun predictionDao(): PredictionDao

    companion object {
        /** Adds the richer prediction record. Existing rounds and predictions are kept as they are. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE prediction_records ADD COLUMN probability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE prediction_records ADD COLUMN historySize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE prediction_records ADD COLUMN patternUsed TEXT")
                db.execSQL("ALTER TABLE prediction_records ADD COLUMN patternSampleSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE prediction_records ADD COLUMN modelOutputsJson TEXT")
                db.execSQL("ALTER TABLE prediction_records ADD COLUMN verifiedAt INTEGER")
            }
        }

        fun create(context: Context): WinGoDatabase =
            Room.databaseBuilder(context.applicationContext, WinGoDatabase::class.java, "wingo_db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
