package dev.jarvis.agent

import dev.jarvis.connectors.phone.EventTime
import dev.jarvis.messaging.NoticesResult
import dev.jarvis.messaging.PhoneNotice
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Other apps' notifications, answered on the phone (D35). Notification text is untrusted data: shown, never obeyed. */
object NotificationAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private const val MAX_APPS = 8

    fun render(tool: String, raw: String, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(NoticesResult.serializer(), raw) }.getOrNull()
            ?: return "I couldn't read the notifications."
        if (r.query != null) {
            if (r.notices.isEmpty()) return "No notifications about \"${r.query}\" in the last 3 days."
            return "Notifications about \"${r.query}\":\n" + r.notices.take(8).joinToString("\n") {
                "• ${it.app} (${whenLabel(it.at, now)}): ${line(it)}"
            }
        }
        if (r.notices.isEmpty()) return "No notifications from other apps in the last day."
        // One line per app, newest app first, with the latest notification and how many there were.
        val byApp = r.notices.groupBy { it.app }
        val lines = byApp.entries.take(MAX_APPS).map { (app, list) ->
            val n = list.first()
            "• $app" + (if (list.size > 1) " (${list.size})" else "") + " — ${line(n)} (${whenLabel(n.at, now)})"
        }
        val more = byApp.size - MAX_APPS
        return "Notifications today:\n" + lines.joinToString("\n") + (if (more > 0) "\n…and $more more apps." else "")
    }

    /** "Title: text", without repeating the title when the text starts with it. */
    private fun line(n: PhoneNotice): String {
        val title = clean(n.title)
        val text = clean(n.text)
        val s = when {
            title.isEmpty() -> text
            text.isEmpty() -> title
            text.startsWith(title) -> text
            else -> "$title: $text"
        }
        return if (s.length > 140) s.take(137) + "…" else s
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun whenLabel(at: String, now: ZonedDateTime): String {
        val t = EventTime.dateTime(at) ?: return at
        return when (ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())) {
            0L -> t.format(TIME)
            1L -> "yesterday " + t.format(TIME)
            else -> t.format(DAY)
        }
    }
}
