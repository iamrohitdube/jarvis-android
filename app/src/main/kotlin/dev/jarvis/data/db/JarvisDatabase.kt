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
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // v2 (Phase 3): messages.sensitive and messages.screenOnly, both defaulting to 0.
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun snapshots(): SnapshotDao
    abstract fun audit(): AuditDao
    abstract fun usage(): UsageDao

    companion object {
        const val NAME = "jarvis.db"
    }
}
