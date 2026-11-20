package dev.jarvis.data.db

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
    version = 1,
    exportSchema = true,
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
