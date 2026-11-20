package dev.jarvis.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jarvis.data.db.UsageDao
import dev.jarvis.data.db.UsageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** USD per million tokens. Source: Claude API pricing (cached 2026-11-03); verify before relying on it. */
data class Price(val input: Double, val output: Double, val cacheWrite: Double, val cacheRead: Double)

object Pricing {
    private const val HAIKU_LONG_PROMPT = 100_000L

    fun priceFor(model: String, promptTokens: Long): Price? = when {
        model.startsWith("claude-sonnet-5-5") -> Price(2.0, 10.0, 2.50, 0.10)
        model.startsWith("claude-opus-5-5") -> Price(4.0, 20.0, 5.0, 0.20)
        model.startsWith("claude-haiku-5-5") ->
            if (promptTokens <= HAIKU_LONG_PROMPT) Price(0.10, 0.50, 0.125, 0.01)
            else Price(0.50, 2.50, 0.625, 0.05)
        else -> null
    }

    /** Estimated cost in USD, or null when the model's price is unknown. */
    fun cost(model: String, u: Usage): Double? {
        val prompt = u.inputTokens + u.cacheCreationInputTokens + u.cacheReadInputTokens
        val p = priceFor(model, prompt) ?: return null
        return (u.inputTokens * p.input + u.outputTokens * p.output +
            u.cacheCreationInputTokens * p.cacheWrite + u.cacheReadInputTokens * p.cacheRead) / 1_000_000.0
    }
}

/** One logged request. Contains no prompt or reply text: only counts, cost and timing. */
@Serializable
data class UsageEntry(
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

enum class BudgetLevel { NONE, OK, WARNING, EXCEEDED }

data class UsageSummary(
    val entries: List<UsageEntry>,
    val monthCostUsd: Double,
    val monthRequests: Int,
    val budgetUsd: Double,
) {
    val budgetFraction: Double get() = if (budgetUsd > 0) monthCostUsd / budgetUsd else 0.0
    val budgetLevel: BudgetLevel
        get() = when {
            budgetUsd <= 0 -> BudgetLevel.NONE
            budgetFraction >= 1.0 -> BudgetLevel.EXCEEDED
            budgetFraction >= WARNING_FRACTION -> BudgetLevel.WARNING
            else -> BudgetLevel.OK
        }

    companion object {
        const val WARNING_FRACTION = 0.8

        fun of(entries: List<UsageEntry>, budgetUsd: Double, now: Instant, zone: ZoneId): UsageSummary {
            val month = YearMonth.from(now.atZone(zone))
            val inMonth = entries.filter { YearMonth.from(Instant.ofEpochMilli(it.timestampMs).atZone(zone)) == month }
            return UsageSummary(entries, inMonth.sumOf { it.costUsd ?: 0.0 }, inMonth.size, budgetUsd)
        }
    }
}

/** Local-only usage log in Room (no telemetry). */
class UsageMeter(
    private val dao: UsageDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val entries: Flow<List<UsageEntry>> = dao.observeAll().map { rows -> rows.map { it.toEntry() } }

    suspend fun record(entry: UsageEntry) {
        dao.insert(entry.toEntity())
    }

    /** Keeps roughly 13 months so the current and previous month are always complete. */
    suspend fun prune() = dao.deleteOlderThan(clock() - RETENTION_DAYS * 24L * 60 * 60 * 1000)

    suspend fun clear() = dao.deleteAll()

    /**
     * One-time import of the Phase 1 DataStore log (JSON list under "usage_log_v1"), then removes it.
     * Safe to call on every start: it does nothing once the old key is gone.
     */
    suspend fun importLegacy(store: DataStore<Preferences>, json: Json) {
        val raw = store.data.first()[LEGACY_KEY] ?: return
        importLegacyJson(raw, json)
        store.edit { it.remove(LEGACY_KEY) }
    }

    /** Imports the Phase 1 JSON list; unreadable data is skipped. Returns how many entries were imported. */
    suspend fun importLegacyJson(raw: String, json: Json): Int {
        val old = runCatching { json.decodeFromString(ListSerializer(UsageEntry.serializer()), raw) }.getOrDefault(emptyList())
        if (old.isNotEmpty()) dao.insertAll(old.map { it.toEntity() })
        return old.size
    }

    companion object {
        const val RETENTION_DAYS = 400L
        val LEGACY_KEY = stringPreferencesKey("usage_log_v1")
    }
}

private fun UsageEntity.toEntry() = UsageEntry(
    timestampMs, model, mode, inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens,
    costUsd, latencyMs, timeToFirstTokenMs, toolsUsed,
)

private fun UsageEntry.toEntity() = UsageEntity(
    timestampMs = timestampMs, model = model, mode = mode, inputTokens = inputTokens, outputTokens = outputTokens,
    cacheWriteTokens = cacheWriteTokens, cacheReadTokens = cacheReadTokens, costUsd = costUsd,
    latencyMs = latencyMs, timeToFirstTokenMs = timeToFirstTokenMs, toolsUsed = toolsUsed,
)
