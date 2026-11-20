package dev.jarvis.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
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

/** Local-only usage log (no telemetry). Phase 2 moves this into Room. */
class UsageMeter(private val store: DataStore<Preferences>, private val json: Json) {
    private val key = stringPreferencesKey("usage_log_v1")
    private val serializer = ListSerializer(UsageEntry.serializer())

    val entries: Flow<List<UsageEntry>> = store.data.map { p -> decode(p[key]) }

    suspend fun record(entry: UsageEntry) {
        store.edit { p ->
            val list = (decode(p[key]) + entry).takeLast(MAX_ENTRIES)
            p[key] = json.encodeToString(serializer, list)
        }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }

    private fun decode(raw: String?): List<UsageEntry> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_ENTRIES = 1000
    }
}
