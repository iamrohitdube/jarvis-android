package dev.jarvis.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Conversation list row with a message count, for the History screen. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertConversation(c: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(m: MessageEntity): Long

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Query(
        """
        SELECT c.id, c.title, c.updatedAt, COUNT(m.id) AS messageCount
        FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id
        GROUP BY c.id ORDER BY c.updatedAt DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationSummary>>

    /** Plain LIKE search over titles and message text; fine at personal-history scale. */
    @Query(
        """
        SELECT c.id, c.title, c.updatedAt, COUNT(m.id) AS messageCount
        FROM conversations c LEFT JOIN messages m ON m.conversationId = c.id
        WHERE c.id IN (
            SELECT id FROM conversations WHERE title LIKE :pattern ESCAPE '\'
            UNION SELECT conversationId FROM messages WHERE text LIKE :pattern ESCAPE '\'
        )
        GROUP BY c.id ORDER BY c.updatedAt DESC
        """
    )
    fun searchConversations(pattern: String): Flow<List<ConversationSummary>>

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun deleteMessagesOf(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationRow(id: Long)

    @Transaction
    suspend fun deleteConversation(id: Long) {
        deleteMessagesOf(id)
        deleteConversationRow(id)
    }

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Transaction
    suspend fun deleteAll() {
        deleteAllMessages()
        deleteAllConversations()
    }
}

@Dao
interface SnapshotDao {
    /** Insert-or-replace by key (REPLACE rather than @Upsert: same result, no error-message sniffing). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SnapshotEntity)

    @Query("SELECT * FROM snapshots WHERE `key` = :key")
    suspend fun get(key: String): SnapshotEntity?

    @Query("SELECT * FROM snapshots ORDER BY fetchedAt DESC")
    fun observeAll(): Flow<List<SnapshotEntity>>

    @Query("DELETE FROM snapshots")
    suspend fun deleteAll()
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(e: AuditEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Query("DELETE FROM audit_log WHERE timestampMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(e: UsageEntity): Long

    @Insert
    suspend fun insertAll(e: List<UsageEntity>)

    @Query("SELECT * FROM usage_log ORDER BY timestampMs")
    fun observeAll(): Flow<List<UsageEntity>>

    @Query("SELECT COUNT(*) FROM usage_log")
    suspend fun count(): Int

    @Query("DELETE FROM usage_log WHERE timestampMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM usage_log")
    suspend fun deleteAll()
}

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE uri = :uri")
    suspend fun get(uri: String): TranscriptEntity?

    @Query("SELECT * FROM transcripts ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TranscriptEntity>

    @Query("SELECT uri FROM transcripts")
    suspend fun knownUris(): List<String>

    @Query("SELECT COUNT(*) FROM transcripts WHERE status = 'done'")
    fun doneCount(): Flow<Int>

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()
}

@Dao
interface CapturedMessageDao {
    @Insert
    suspend fun insert(m: CapturedMessageEntity): Long

    @Query("SELECT * FROM captured_messages WHERE at >= :since ORDER BY at DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<CapturedMessageEntity>

    /** Drop a notification's reply handle once it's gone (the reply action no longer works). */
    @Query("UPDATE captured_messages SET replyKey = NULL WHERE replyKey = :key")
    suspend fun clearReplyKey(key: String)

    @Query("DELETE FROM captured_messages WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM captured_messages")
    suspend fun deleteAll()
}

@Dao
interface CapturedNotificationDao {
    @Insert
    suspend fun insert(n: CapturedNotificationEntity): Long

    @Query("SELECT * FROM captured_notifications WHERE at >= :since ORDER BY at DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<CapturedNotificationEntity>

    @Query("DELETE FROM captured_notifications WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM captured_notifications")
    suspend fun deleteAll()
}

@Dao
interface SentMessageDao {
    @Insert
    suspend fun insert(m: SentMessageEntity): Long

    @Query("UPDATE sent_messages SET sentAt = :sentAt, outcome = :outcome WHERE id = :id")
    suspend fun finish(id: Long, sentAt: Long?, outcome: String)

    @Query("SELECT COUNT(*) FROM sent_messages WHERE approvedAt >= :since AND outcome = 'sent'")
    suspend fun sentSince(since: Long): Int

    @Query("SELECT * FROM sent_messages ORDER BY approvedAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SentMessageEntity>>
}
