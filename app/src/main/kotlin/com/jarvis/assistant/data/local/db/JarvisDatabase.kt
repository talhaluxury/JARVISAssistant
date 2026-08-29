package com.jarvis.assistant.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jarvis.assistant.data.local.db.dao.ConversationDao
import com.jarvis.assistant.data.local.db.dao.MemoryDao
import com.jarvis.assistant.data.local.db.dao.MessageDao
import com.jarvis.assistant.data.local.db.entity.ConversationEntity
import com.jarvis.assistant.data.local.db.entity.MemoryEntity
import com.jarvis.assistant.data.local.db.entity.MessageEntity

@Database(
    entities = [MemoryEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: JarvisDatabase? = null

        fun getInstance(context: Context): JarvisDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_db"
                ).build().also { INSTANCE = it }
            }
    }
}
