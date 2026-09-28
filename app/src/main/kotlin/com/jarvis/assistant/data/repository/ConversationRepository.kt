package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.local.db.dao.ConversationDao
import com.jarvis.assistant.data.local.db.dao.MessageDao
import com.jarvis.assistant.data.local.db.entity.ConversationEntity
import com.jarvis.assistant.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        conversationDao.search(query)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeForConversation(conversationId)

    suspend fun createConversation(title: String): Long =
        conversationDao.insert(ConversationEntity(title = title))

    suspend fun renameConversation(conversation: ConversationEntity, newTitle: String) {
        conversationDao.update(conversation.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
    }

    suspend fun touch(conversation: ConversationEntity) {
        conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun addMessage(conversationId: Long, role: String, content: String) {
        messageDao.insert(MessageEntity(conversationId = conversationId, role = role, content = content))
    }

    suspend fun getHistory(conversationId: Long): List<MessageEntity> =
        messageDao.getForConversation(conversationId)

    suspend fun deleteConversation(conversation: ConversationEntity) =
        conversationDao.delete(conversation)

    suspend fun clearAll() = conversationDao.clearAll()
}
