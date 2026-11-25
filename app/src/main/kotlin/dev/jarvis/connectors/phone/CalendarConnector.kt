package dev.jarvis.connectors.phone

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import dev.jarvis.agent.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One event occurrence as the phone keeps it. Times are local ("2026-11-25T16:00"); all-day events are dates. */
@Serializable
data class CalendarEvent(
    val title: String,
    val start: String,
    val end: String,
    val all_day: Boolean = false,
    val location: String = "",
    val calendar: String = "",
)

@Serializable
data class CalendarResult(val range: String, val query: String? = null, val events: List<CalendarEvent> = emptyList())

/** Where events come from (Android's calendar provider in the app; a list in tests). */
fun interface CalendarSource {
    /** Occurrences overlapping [fromMs, toMs), sorted by start; declined events left out. */
    fun events(fromMs: Long, toMs: Long): List<CalendarEvent>
}

/**
 * The calendar Android already syncs (all accounts), read through CalendarContract with READ_CALENDAR
 * (user's pick, D29, 2026-11-25). Read-only; nothing leaves the phone.
 */
class CalendarConnector(
    private val source: CalendarSource,
    private val json: Json,
    granted: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : LocalConnector(
    ToolRegistry.CALENDAR, "Calendar", granted, PERMISSIONS,
    "Today's events, your next meeting and when something is. Read-only.",
) {

    override suspend fun read(tool: String, arguments: JsonObject): String {
        val now = Instant.ofEpochMilli(clock()).atZone(zone())
        val result = when (tool) {
            TODAY -> {
                val start = now.toLocalDate().atStartOfDay(zone())
                CalendarResult("today", events = source.events(start.toInstant().toEpochMilli(), start.plusDays(1).toInstant().toEpochMilli()))
            }
            UPCOMING -> CalendarResult(
                "next 7 days",
                events = source.events(now.toInstant().toEpochMilli(), now.plusDays(7).toInstant().toEpochMilli()).take(MAX_EVENTS),
            )
            SEARCH -> {
                val q = arguments.text("query") ?: throw IllegalArgumentException("Nothing to search for")
                val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                val found = source.events(now.minusDays(7).toInstant().toEpochMilli(), now.plusDays(SEARCH_DAYS).toInstant().toEpochMilli())
                    .filter { e -> (e.title + " " + e.location).lowercase().let { hay -> words.all { hay.contains(it) } } }
                CalendarResult("search", q, found.take(MAX_EVENTS))
            }
            else -> throw IllegalArgumentException("Unknown calendar tool")
        }
        return json.encodeToString(CalendarResult.serializer(), result)
    }

    companion object {
        const val TODAY = "calendar_today"
        const val UPCOMING = "calendar_upcoming"
        const val SEARCH = "calendar_search"
        const val MAX_EVENTS = 25
        const val SEARCH_DAYS = 60L
        val PERMISSIONS = listOf(android.Manifest.permission.READ_CALENDAR)
    }
}

/** Android's calendar provider (event instances, so repeating events appear once per occurrence). */
class AndroidCalendarSource(private val resolver: ContentResolver, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : CalendarSource {
    override fun events(fromMs: Long, toMs: Long): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = mutableListOf<CalendarEvent>()
        resolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(3) == 1
                out += CalendarEvent(
                    title = c.getString(0)?.trim().orEmpty().ifEmpty { "(no title)" }.take(120),
                    start = stamp(c.getLong(1), allDay),
                    end = stamp(c.getLong(2), allDay),
                    all_day = allDay,
                    location = c.getString(4)?.trim().orEmpty().take(80),
                    calendar = c.getString(5)?.trim().orEmpty().take(60),
                )
            }
        }
        return out
    }

    /** All-day events are stored at UTC midnight: keep them as dates. */
    private fun stamp(ms: Long, allDay: Boolean): String =
        if (allDay) Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()
        else LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone()).format(LOCAL)

    private companion object {
        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}

/** Parses the connector's local time stamps ("2026-11-25T16:00" or "2026-11-25"). */
object EventTime {
    fun dateTime(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s) }.getOrNull()
    fun date(s: String): LocalDate? = dateTime(s)?.toLocalDate() ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
}
