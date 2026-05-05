package dev.krinry.jarvis.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesOnce(): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE timestamp >= :cutoff ORDER BY timestamp ASC")
    fun getRecentMessages(cutoff: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastMessages(limit: Int): List<ChatMessage>

    @Insert
    suspend fun insertMessage(msg: ChatMessage): Long

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun getMessageCount(): Int

    @Query("SELECT * FROM chat_messages WHERE role = 'user' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastUserMessage(): ChatMessage?
}
