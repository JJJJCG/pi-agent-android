package com.pi.assistant.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    /** 重发用：干掉指定消息之后的所有气泡，避免历史错乱。 */
    @Query("DELETE FROM messages WHERE id > :id")
    suspend fun deleteAfter(id: Long)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MessageEntity?

    /** 请求失败时给那条用户气泡打标记，UI 上就能显示「未送达 · 重发」。 */
    @Query("UPDATE messages SET failed = :failed WHERE id = :id")
    suspend fun setFailed(id: Long, failed: Boolean)

    @Query("DELETE FROM messages")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int
}
