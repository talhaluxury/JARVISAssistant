package com.jarvis.assistant.wingo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Deliberately a SEPARATE database file from JarvisDatabase: the existing memory/brain schema and its
 * migrations stay untouched, so adding this module cannot corrupt or wipe anything JARVIS already has.
 */
@Database(
    entities = [GameResultEntity::class, PredictionRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class WinGoDatabase : RoomDatabase() {
    abstract fun gameResultDao(): GameResultDao
    abstract fun predictionDao(): PredictionDao

    companion object {
        fun create(context: Context): WinGoDatabase =
            Room.databaseBuilder(context.applicationContext, WinGoDatabase::class.java, "wingo_db").build()
    }
}
