package dev.jarvis.messaging

import dev.jarvis.agent.ToolRegistry
import dev.jarvis.connectors.phone.LocalConnector
import dev.jarvis.data.db.CapturedNotificationDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A notification from a non-chat app (bank alert, delivery, reminder…), as JARVIS keeps it (D35). */
data class AppNotice(
    val app: String,
    val packageName: String,
    val title: String,
    val text: String,
    val category: String?,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val secret: Boolean = false,
)

/**
 * Pure rules for which other-app notifications are kept (D35). Status notifications (music, downloads, navigation,
 * calls), group summaries, notifications the app marks secret, one-time codes and apps the user ignored are dropped.
 */
object AppNotices {
    /** Notification.CATEGORY_* values that are status or are covered elsewhere (calls come from the call log). */
    val SKIP_CATEGORIES = setOf(
        "call", "missed_call", "navigation", "progress", "service", "sys", "transport", "stopwatch", "alarm",
        "location_sharing", "workout",
    )

    /** System packages whose notifications are phone status, not news. */
    val SKIP_PACKAGES = setOf("android", "com.android.systemui", "com.android.providers.downloads", "com.google.android.gms")

    /** Captured notifications are kept 3 days (same as chat messages). */
    const val KEEP_MS = 3 * 24 * 3600_000L

    /** The user's ignore list: comma-separated app names (or package names). */
    fun ignored(list: String): List<String> =
        list.split(',').map { it.trim().lowercase(Locale.ENGLISH) }.filter { it.isNotEmpty() }

    fun keep(n: AppNotice, ownPackage: String, ignore: List<String>): Boolean {
        if (n.packageName == ownPackage || n.packageName in SKIP_PACKAGES || n.packageName in ChatNotices.APPS) return false
        if (n.ongoing || n.groupSummary || n.secret) return false
        if (n.category != null && n.category in SKIP_CATEGORIES) return false
        if (n.title.isBlank() && n.text.isBlank()) return false
        if (ChatNotices.isCode(n.title + " " + n.text)) return false
        val app = n.app.lowercase(Locale.ENGLISH)
        return ignore.none { it == app || it == n.packageName.lowercase(Locale.ENGLISH) }
    }

    private val recent = ArrayDeque<Int>()

    /** True the first time a (package, title, text) is seen among the last 500 (apps re-post the same alert). */
    @Synchronized fun firstTime(n: AppNotice): Boolean {
        val h = listOf(n.packageName, n.title, n.text).hashCode()
        if (h in recent) return false
        recent.addLast(h)
        while (recent.size > 500) recent.removeFirst()
        return true
    }

    /** Fallback app name when Android won't give the label: "com.example.shopping" -> "Shopping". */
    fun nameFromPackage(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar { it.titlecase(Locale.ENGLISH) }.ifBlank { pkg }
}

@Serializable
data class PhoneNotice(val app: String, val title: String, val text: String, val at: String)

@Serializable
data class NoticesResult(val query: String? = null, val notices: List<PhoneNotice> = emptyList())

/**
 * Notifications from other apps (D35), read from the local store the notification listener fills. Needs notification
 * access AND the user's "Other app notifications" switch. Content is untrusted DATA, answered on the phone.
 */
class AlertsConnector(
    private val dao: CapturedNotificationDao,
    private val json: Json,
    granted: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : LocalConnector(
    ToolRegistry.ALERTS, "App notifications", granted, emptyList(),
    "Notifications from your other apps (bank alerts, deliveries, reminders), kept 3 days. Chats and codes are skipped.",
) {
    override suspend fun read(tool: String, arguments: JsonObject): String {
        val query = if (tool == SEARCH) (arguments["query"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Nothing to look for") else null
        val since = clock() - if (query == null) DAY else AppNotices.KEEP_MS
        val rows = dao.since(since, 400).filter { query == null || matches(it.app + " " + it.title + " " + it.text, query) }
        val notices = rows.take(if (query == null) 40 else 12).map {
            PhoneNotice(it.app, it.title.take(120), it.text.take(300), local(it.at))
        }
        return json.encodeToString(NoticesResult.serializer(), NoticesResult(query, notices))
    }

    private fun local(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()).format(LOCAL)

    companion object {
        const val RECENT = "notifications_recent"
        const val SEARCH = "notifications_search"
        private const val DAY = 24 * 3600_000L
        private val LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** Every word of [query] appears in [hay] (case-insensitive). */
        fun matches(hay: String, query: String): Boolean {
            val h = hay.lowercase(Locale.ENGLISH)
            val words = query.lowercase(Locale.ENGLISH).split(Regex("\\s+")).filter { it.isNotBlank() }
            return words.isNotEmpty() && words.all { h.contains(it) }
        }
    }
}
