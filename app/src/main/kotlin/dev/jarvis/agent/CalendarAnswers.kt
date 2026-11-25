package dev.jarvis.agent

import dev.jarvis.connectors.phone.CalendarEvent
import dev.jarvis.connectors.phone.CalendarResult
import dev.jarvis.connectors.phone.EventTime
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Calendar answers built on the phone from the calendar connector (D29). Short and voice-friendly. */
object CalendarAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SHOW = 8
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun render(tool: String, raw: String, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(CalendarResult.serializer(), raw) }.getOrNull()
            ?: return "I couldn't read the calendar."
        return when (tool) {
            "calendar_today" -> today(r.events, now)
            "calendar_search" -> search(r.events, r.query.orEmpty(), now)
            else -> upcoming(r.events, now)
        }
    }

    private fun today(events: List<CalendarEvent>, now: ZonedDateTime): String {
        if (events.isEmpty()) return "Nothing on your calendar today."
        val left = events.count { e -> !e.all_day && (EventTime.dateTime(e.end)?.let { it >= now.toLocalDateTime() } ?: true) }
        val head = (if (events.size == 1) "1 event today" else "${events.size} events today") +
            (if (left in 1 until events.size) ", $left still to come" else "") + ":\n"
        return head + events.take(SHOW).joinToString("\n") { "• " + line(it, now, withDay = false) } + more(events.size)
    }

    private fun upcoming(events: List<CalendarEvent>, now: ZonedDateTime): String {
        if (events.isEmpty()) return "Nothing on your calendar in the next 7 days."
        val next = events.firstOrNull { !it.all_day } ?: events.first()
        return "Next: " + line(next, now, withDay = true) + ".\n" +
            (if (events.size > 1) "Coming up:\n" + events.take(SHOW).joinToString("\n") { "• " + line(it, now, withDay = true) } + more(events.size) else "")
    }

    private fun search(events: List<CalendarEvent>, query: String, now: ZonedDateTime): String {
        if (events.isEmpty()) return "Nothing matching \"$query\" on your calendar."
        return (if (events.size == 1) "1 event for \"$query\":\n" else "${events.size} events for \"$query\":\n") +
            events.take(SHOW).joinToString("\n") { "• " + line(it, now, withDay = true) } + more(events.size)
    }

    /** "Thu 27 Nov, 10:00 AM–10:30 AM Standup (Room 4)" / "Tomorrow, all day: Holiday". */
    fun line(e: CalendarEvent, now: ZonedDateTime, withDay: Boolean): String {
        val when_ = if (e.all_day) "all day" else {
            val s = EventTime.dateTime(e.start)
            val end = EventTime.dateTime(e.end)
            val sameDay = s != null && end != null && s.toLocalDate() == end.toLocalDate()
            listOfNotNull(s?.format(TIME), end?.takeIf { sameDay }?.format(TIME)).joinToString("–")
        }
        val day = if (withDay) EventTime.date(e.start)?.let { dayLabel(it, now) }?.plus(", ").orEmpty() else ""
        val where = e.location.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$day$when_ ${e.title}$where".trim()
    }

    private fun dayLabel(d: java.time.LocalDate, now: ZonedDateTime): String = when (ChronoUnit.DAYS.between(now.toLocalDate(), d)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> d.format(DAY)
    }

    private fun more(total: Int) = if (total > SHOW) "\n…and ${total - SHOW} more." else ""
}
