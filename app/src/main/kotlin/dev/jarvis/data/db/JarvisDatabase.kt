package dev.jarvis.data.db

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * Local app database (CLAUDE.md section 11 `data/`). Stored in the app's private storage, which is
 * excluded from Android backup (data_extraction_rules.xml). Version bumps need a tested migration.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        SnapshotEntity::class,
        AuditEntity::class,
        UsageEntity::class,
        TranscriptEntity::class,
        CapturedMessageEntity::class,
        SentMessageEntity::class,
        CapturedNotificationEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // v2 (Phase 3): messages.sensitive and messages.screenOnly, both defaulting to 0.
        AutoMigration(from = 1, to = 2),
        // v3 (Phase 6): transcripts, captured_messages, sent_messages (new tables only).
        AutoMigration(from = 2, to = 3),
        // v4 (Phase 12, D35): captured_notifications (new table only).
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun snapshots(): SnapshotDao
    abstract fun audit(): AuditDao
    abstract fun usage(): UsageDao
    abstract fun transcripts(): TranscriptDao
    abstract fun capturedMessages(): CapturedMessageDao
    abstract fun sentMessages(): SentMessageDao
    abstract fun capturedNotifications(): CapturedNotificationDao

    companion object {
        const val NAME = "jarvis.db"
    }
}
