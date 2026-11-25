package dev.jarvis.agent

import dev.jarvis.connectors.phone.CallEntry
import dev.jarvis.connectors.phone.CallLogResult
import dev.jarvis.connectors.phone.EventTime
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Call-log answers built on the phone (D30). Unknown numbers are shown by their last digits only. */
object CallAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SHOW = 8
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun render(tool: String, raw: String, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(CallLogResult.serializer(), raw) }.getOrNull()
            ?: return "I couldn't read the call log."
        return when (tool) {
            "calls_missed" -> missed(r.calls, now)
            "calls_with" -> with(r.calls, r.query.orEmpty(), now)
            else -> recent(r.calls, now)
        }
    }

    private fun recent(calls: List<CallEntry>, now: ZonedDateTime): String {
        if (calls.isEmpty()) return "No calls in the last 30 days."
        return "Recent calls:\n" + calls.take(SHOW).joinToString("\n") { c ->
            "• ${who(c)}, ${c.type}${duration(c)}, ${whenLabel(c.at, now)}"
        }
    }

    private fun missed(calls: List<CallEntry>, now: ZonedDateTime): String {
        if (calls.isEmpty()) return "No missed calls in the last 7 days."
        val byPerson = calls.groupBy { who(it) }
        val head = if (calls.size == 1) "1 missed call this week:\n" else "${calls.size} missed calls this week:\n"
        return head + byPerson.entries.take(SHOW).joinToString("\n") { (person, list) ->
            "• $person" + (if (list.size > 1) " (${list.size}×)" else "") + ", last ${whenLabel(list.first().at, now)}"
        }
    }

    private fun with(calls: List<CallEntry>, query: String, now: ZonedDateTime): String {
        if (calls.isEmpty()) return "No calls with \"$query\" in the last 90 days."
        val last = calls.first()
        val talked = calls.filter { it.duration_s > 0 }
        val minutes = talked.sumOf { it.duration_s } / 60
        val lastTalk = talked.firstOrNull()
        return buildString {
            append("Last call with ${who(last)}: ${last.type}, ${whenLabel(last.at, now)}${duration(last)}.")
            if (lastTalk != null && lastTalk != last) append(" You last talked ${whenLabel(lastTalk.at, now)}${duration(lastTalk)}.")
            append(" ${calls.size} call${if (calls.size == 1) "" else "s"} in 90 days")
            if (minutes > 0) append(", about $minutes min in total")
            append('.')
        }
    }

    /** The contact name, or "number ending 1234" for unknown callers. */
    fun who(c: CallEntry): String =
        c.name.ifBlank { c.number.filter { it.isDigit() }.takeLast(4).takeIf { it.isNotEmpty() }?.let { "number ending $it" } ?: "Unknown number" }

    private fun duration(c: CallEntry): String = when {
        c.duration_s <= 0 -> ""
        c.duration_s < 60 -> " (${c.duration_s} s)"
        else -> " (${c.duration_s / 60} min)"
    }

    private fun whenLabel(at: String, now: ZonedDateTime): String {
        val t = EventTime.dateTime(at) ?: return at
        return when (ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())) {
            0L -> "today " + t.format(TIME)
            1L -> "yesterday " + t.format(TIME)
            else -> t.format(DAY)
        }
    }
}
