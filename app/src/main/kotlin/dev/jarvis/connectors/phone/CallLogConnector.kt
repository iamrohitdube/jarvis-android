package dev.jarvis.connectors.phone

import android.content.ContentResolver
import android.provider.CallLog
import dev.jarvis.agent.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One call as the phone keeps it. [name] is the contact name Android cached, or "" for unknown numbers. */
@Serializable
data class CallEntry(
    val name: String,
    val number: String,
    /** incoming, outgoing, missed, rejected, blocked, voicemail. */
    val type: String,
    /** Local time, "2026-11-25T10:05". */
    val at: String,
    val duration_s: Long,
)

@Serializable
data class CallLogResult(val range: String, val query: String? = null, val calls: List<CallEntry> = emptyList())

/** Where calls come from (Android's call log in the app; a list in tests). Newest first. */
fun interface CallLogSource {
    fun calls(sinceMs: Long, limit: Int): List<CallEntry>
}

/**
 * The phone's call log (D30), read with READ_CALL_LOG, answered on the phone. Numbers and names never leave it.
 * READ_CALL_LOG is a "restricted" permission for apps installed from a downloaded APK: the user may first need
 * App info → ⋮ → Allow restricted settings (Settings → Phone data explains this).
 */
class CallLogConnector(
    private val source: CallLogSource,
    private val json: Json,
    granted: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalConnector(
    ToolRegistry.CALLS, "Call log", granted, PERMISSIONS,
    "Who called, missed calls, and when you last spoke to someone. Read-only.",
) {
    override suspend fun read(tool: String, arguments: JsonObject): String {
        val now = clock()
        val result = when (tool) {
            RECENT -> CallLogResult("recent", calls = source.calls(now - 30 * DAY, RECENT_COUNT))
            MISSED -> CallLogResult("missed, last 7 days", calls = source.calls(now - 7 * DAY, 300).filter { it.type == "missed" }.take(50))
            WITH -> {
                val q = arguments.text("query") ?: throw IllegalArgumentException("Nobody to look for")
                CallLogResult("last 90 days", q, source.calls(now - 90 * DAY, 2000).filter { matches(it, q) }.take(100))
            }
            else -> throw IllegalArgumentException("Unknown call log tool")
        }
        return json.encodeToString(CallLogResult.serializer(), result)
    }

    companion object {
        const val RECENT = "calls_recent"
        const val MISSED = "calls_missed"
        const val WITH = "calls_with"
        const val RECENT_COUNT = 20
        private const val DAY = 24 * 3600_000L
        val PERMISSIONS = listOf(android.Manifest.permission.READ_CALL_LOG)

        /** A name match (every word) or a number match (the digits given appear in the number). */
        fun matches(c: CallEntry, query: String): Boolean {
            val digits = query.filter { it.isDigit() }
            if (digits.length >= 4) return c.number.filter { it.isDigit() }.contains(digits.takeLast(10))
            val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            return words.isNotEmpty() && words.all { c.name.lowercase().contains(it) }
        }
    }
}

/** Android's call log. Uses the provider's limit parameter (it rejects LIMIT in the sort order). */
class AndroidCallLogSource(private val resolver: ContentResolver, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : CallLogSource {
    override fun calls(sinceMs: Long, limit: Int): List<CallEntry> {
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString()).build()
        val projection = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val out = mutableListOf<CallEntry>()
        resolver.query(uri, projection, "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMs.toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                out += CallEntry(
                    name = c.getString(0)?.trim().orEmpty().take(60),
                    number = c.getString(1)?.trim().orEmpty().take(30),
                    type = typeName(c.getInt(2)),
                    at = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(3)), zone()).format(LOCAL),
                    duration_s = c.getLong(4),
                )
            }
        }
        return out
    }

    private fun typeName(t: Int) = when (t) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "other"
    }

    private companion object {
        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}
