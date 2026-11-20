package dev.jarvis.data

import dev.jarvis.agent.ToolSpec
import dev.jarvis.data.db.SnapshotDao
import dev.jarvis.data.db.SnapshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * Cached results of whole-account connector reads ("snapshots"), e.g. INDmoney net worth.
 * Reusing a fresh snapshot skips the MCP round-trip and its tokens; every reply that uses one
 * carries a "last updated" label.
 */
class SnapshotStore(
    private val dao: SnapshotDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val all: Flow<List<SnapshotEntity>> get() = dao.observeAll()

    suspend fun save(connectorId: String, tool: String, content: String, at: Long = clock()) {
        dao.upsert(SnapshotEntity(key(connectorId, tool), connectorId, tool, content.take(MAX_CONTENT_CHARS), at))
    }

    /**
     * Returns snapshots for every routed tool when all of them are snapshot tools with a result
     * younger than [maxAgeMs]; otherwise null (the request must fetch live data). Helpers are ignored.
     */
    suspend fun freshFor(tools: List<ToolSpec>, maxAgeMs: Long): List<SnapshotEntity>? {
        if (maxAgeMs <= 0) return null
        val needed = tools.filterNot { it.helper }
        if (needed.isEmpty() || needed.any { !it.snapshot || it.connectorId == null }) return null
        val now = clock()
        return needed.map { spec ->
            val s = dao.get(key(spec.connectorId!!, spec.name)) ?: return null
            if (now - s.fetchedAt > maxAgeMs || s.fetchedAt > now) return null
            s
        }
    }

    suspend fun clear() = dao.deleteAll()

    companion object {
        /** Stored results are clipped; a full holdings list is still well under this. */
        const val MAX_CONTENT_CHARS = 20_000
        fun key(connectorId: String, tool: String) = "$connectorId/$tool"
    }
}
