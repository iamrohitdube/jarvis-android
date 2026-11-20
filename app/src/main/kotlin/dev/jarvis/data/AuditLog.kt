package dev.jarvis.data

import dev.jarvis.data.db.AuditDao
import dev.jarvis.data.db.AuditEntity
import kotlinx.coroutines.flow.Flow

enum class AuditCategory { REQUEST, TOOL, CONNECTOR, DATA, AUTH }

/**
 * One audited event (CLAUDE.md section 6: time, tool, tier, method, outcome). Callers pass only
 * metadata. [detail] is for short codes like "cached" or an OAuth error code, never content.
 */
data class AuditEvent(
    val category: AuditCategory,
    val action: String,
    val outcome: String,
    val tool: String? = null,
    val tier: String? = null,
    val method: String? = null,
    val detail: String? = null,
)

/** Where audit events go. [NONE] is for tests and code paths that don't audit. */
fun interface AuditSink {
    suspend fun record(event: AuditEvent)

    companion object {
        val NONE = AuditSink { }
    }
}

class AuditLog(
    private val dao: AuditDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuditSink {

    fun recent(limit: Int = 500): Flow<List<AuditEntity>> = dao.observeRecent(limit)

    override suspend fun record(event: AuditEvent) {
        dao.insert(
            AuditEntity(
                timestampMs = clock(),
                category = event.category.name,
                action = event.action.take(MAX_FIELD),
                tool = event.tool?.take(MAX_FIELD),
                tier = event.tier,
                method = event.method,
                outcome = event.outcome.take(MAX_FIELD),
                detail = event.detail?.take(MAX_FIELD),
            )
        )
    }

    /** Keeps the last [RETENTION_DAYS] days. Called at app start. */
    suspend fun prune() = dao.deleteOlderThan(clock() - RETENTION_DAYS * DAY_MS)

    companion object {
        const val RETENTION_DAYS = 180L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** Hard cap per field so nothing long (like content) can end up in the log by mistake. */
        const val MAX_FIELD = 80
    }
}
