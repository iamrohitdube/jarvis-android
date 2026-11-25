package dev.jarvis.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** A chat conversation. Title is derived from the first user message. */
@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One message, verbatim (CLAUDE.md section 5, layer 2: history). [dataSources] is a short label of
 * connector data the reply used, e.g. "INDmoney|1732100000000|cached", so the UI can show "last updated".
 */
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val createdAt: Long,
    val model: String? = null,
    val costUsd: Double? = null,
    val dataSources: String? = null,
    /** True for failed replies; kept for the record but never resent to the model. */
    val failed: Boolean = false,
    /** Contains Tier 2 data: text is hidden in Chat/History until a sensitive session is active. */
    @ColumnInfo(defaultValue = "0") val sensitive: Boolean = false,
    /** Delivered via fingerprint: show on screen, never speak it (used by voice in Phase 4). */
    @ColumnInfo(defaultValue = "0") val screenOnly: Boolean = false,
)

/**
 * Latest result of a read-only connector tool, e.g. INDmoney `networth_snapshot`. Reused for a
 * short time to save tokens and shown with a "last updated" label. Key = "connectorId/tool".
 */
@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey val key: String,
    val connectorId: String,
    val tool: String,
    val content: String,
    val fetchedAt: Long,
)

/**
 * Audit log row (CLAUDE.md section 6): time, category, action, tool, tier, method, outcome.
 * Never contains message text, tool output, tokens, PINs or keys.
 */
@Entity(tableName = "audit_log", indices = [Index("timestampMs")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val category: String,
    val action: String,
    val tool: String? = null,
    val tier: String? = null,
    val method: String? = null,
    val outcome: String,
    val detail: String? = null,
)

/** One API request's usage (counts, cost and timing only; no text). */
@Entity(tableName = "usage_log", indices = [Index("timestampMs")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val model: String,
    val mode: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val costUsd: Double?,
    val latencyMs: Long,
    val timeToFirstTokenMs: Long?,
    val toolsUsed: Int,
)

/**
 * On-phone transcript of one call recording (D30). The recording stays where the dialer saved it; only the text
 * (made on the phone) is kept here, never sent anywhere. [status]: done, failed, too_long, no_models.
 */
@Entity(tableName = "transcripts", indices = [Index("recordedAt")])
data class TranscriptEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val recordedAt: Long,
    val durationMs: Long,
    val text: String,
    val status: String,
    val updatedAt: Long,
)

/**
 * A chat message seen in another app's notification (WhatsApp, Telegram, SMS apps…), kept briefly so JARVIS can
 * read it back and reply through the notification (D29 step 8). [replyKey] = notification key while it can be replied to.
 */
@Entity(tableName = "captured_messages", indices = [Index("at"), Index("sender")])
data class CapturedMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val app: String,
    val packageName: String,
    val sender: String,
    val conversation: String,
    val text: String,
    val at: Long,
    val isGroup: Boolean,
    val replyKey: String?,
)

/** Every message JARVIS sent, with what was approved and when (CLAUDE.md section 7: log every send). */
@Entity(tableName = "sent_messages", indices = [Index("approvedAt")])
data class SentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val recipient: String,
    val text: String,
    val approvedAt: Long,
    val sentAt: Long?,
    val outcome: String,
)
