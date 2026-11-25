package dev.jarvis.agent

import dev.jarvis.connectors.google.EmailHeader
import dev.jarvis.connectors.google.EmailTriage
import dev.jarvis.connectors.google.InboxResult
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Email answers built ON THE PHONE from the Gmail connector's filtered, triaged headers (D29). No LLM: short,
 * voice-friendly templates. Important emails (bills, people, jobs, government, security, bank, travel, orders)
 * come first; newsletters and social notifications are only counted. Sender names are shown without addresses.
 */
object EmailAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SHOW = 6

    fun render(tool: String, raw: String, item: String?, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(InboxResult.serializer(), raw) }.getOrNull()
            ?: return "I couldn't read the email result."
        return when (tool) {
            "gmail_unread" -> unread(r, now)
            "gmail_search" -> search(r.messages, r.query ?: item.orEmpty(), r.hidden, now)
            // Latest emails; with an item (offline "email from X") the saved list is filtered on the phone.
            else -> if (item != null) search(r.messages.filter { matches(it, item) }, item, 0, now) else recent(r, now)
        }
    }

    private fun unread(r: InboxResult, now: ZonedDateTime): String {
        val count = r.estimate ?: r.messages.count { it.unread }
        if (count == 0) return "No unread emails."
        val head = if (count == 1) "You have 1 unread email." else "You have $count unread emails."
        return head + " " + triaged(r.messages, now, emptyNote = "None of the latest look important") + hiddenNote(r.hidden)
    }

    private fun recent(r: InboxResult, now: ZonedDateTime): String {
        if (r.messages.isEmpty()) return "No emails to show." + hiddenNote(r.hidden)
        return triaged(r.messages, now, emptyNote = "Nothing important in your latest emails") + hiddenNote(r.hidden)
    }

    /** Important emails as lines, then one line counting the rest. */
    private fun triaged(messages: List<EmailHeader>, now: ZonedDateTime, emptyNote: String): String {
        val sorted = EmailTriage.sort(messages)
        val important = sorted.filter { EmailTriage.kindOf(it).important }
        val updates = sorted.count { EmailTriage.kindOf(it) == EmailTriage.Kind.OTHER }
        val noise = sorted.count { EmailTriage.kindOf(it) == EmailTriage.Kind.NOISE }
        if (important.isEmpty()) {
            val others = listOfNotNull(
                updates.takeIf { it > 0 }?.let { if (it == 1) "1 routine update" else "$it routine updates" },
                noise.takeIf { it > 0 }?.let { if (it == 1) "1 newsletter or social post" else "$it newsletters or social posts" },
            ).joinToString(" and ")
            return if (others.isEmpty()) "$emptyNote." else "$emptyNote, just $others."
        }
        val rest = restNote(updates, noise, important.size - SHOW)
        val title = if (important.size == 1) "1 needs attention:\n" else "${important.size} need attention:\n"
        return title + lines(important, now) + rest
    }

    private fun restNote(updates: Int, noise: Int, moreImportant: Int): String {
        val parts = buildList {
            if (moreImportant > 0) add(if (moreImportant == 1) "1 more important email" else "$moreImportant more important emails")
            if (updates > 0) add(if (updates == 1) "1 routine update" else "$updates routine updates")
            if (noise > 0) add(if (noise == 1) "1 newsletter or social post skipped" else "$noise newsletters or social posts skipped")
        }
        return if (parts.isEmpty()) "" else "\nAlso " + parts.joinToString(", ") + "."
    }

    private fun search(messages: List<EmailHeader>, query: String, hidden: Int, now: ZonedDateTime): String {
        val q = query.removePrefix("from:").trim()
        if (messages.isEmpty()) return "No recent emails found for \"$q\"." + hiddenNote(hidden)
        val n = messages.size
        return (if (n == 1) "1 email for \"$q\":\n" else "$n emails for \"$q\":\n") +
            lines(EmailTriage.sort(messages), now) + hiddenNote(hidden)
    }

    private fun lines(messages: List<EmailHeader>, now: ZonedDateTime): String =
        messages.take(SHOW).joinToString("\n") { line(it, now) }

    /** "• Bill · HDFC Bank: Credit card statement, ₹12,345 due 05/12/2026 (9:05 AM)" */
    fun line(e: EmailHeader, now: ZonedDateTime): String {
        val kind = EmailTriage.kindOf(e)
        val tag = kind.label?.let { "$it · " }.orEmpty()
        val subject = e.subject.ifBlank { "(no subject)" }
        val money = listOfNotNull(e.amount, e.due?.let { "due $it" }).joinToString(" ")
        val detail = if (money.isNotEmpty()) "$subject, $money" else subject
        return "• $tag${senderName(e.from)}: $detail (${whenLabel(e.at, now)})"
    }

    private fun hiddenNote(hidden: Int): String = when {
        hidden <= 0 -> ""
        hidden == 1 -> "\n1 hidden by your privacy filter."
        else -> "\n$hidden hidden by your privacy filter."
    }

    /** "Amazon.in <ship@amazon.in>" -> "Amazon.in"; a bare address -> the part before @. */
    fun senderName(from: String): String {
        val name = from.substringBefore('<').trim().trim('"').trim()
        if (name.isNotEmpty() && '@' !in name) return name
        val address = from.substringAfter('<').substringBefore('>').trim()
        return address.substringBefore('@').ifBlank { "Unknown sender" }
    }

    fun whenLabel(epochMs: Long, now: ZonedDateTime): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(now.zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        return when {
            days <= 0L -> t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            days == 1L -> "yesterday"
            else -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        }
    }

    /** Offline filter: every word of [item] appears in the sender or subject. */
    internal fun matches(e: EmailHeader, item: String): Boolean {
        val hay = (e.from + " " + e.subject).lowercase()
        return item.removePrefix("from:").lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { hay.contains(it) }
    }
}

/** Picks the on-phone template for a personal tool's result (D29). */
object PhoneAnswers {
    fun render(spec: ToolSpec, raw: String, question: String, item: String?, now: ZonedDateTime): String = when (spec.group) {
        ToolGroup.EMAIL -> EmailAnswers.render(spec.name, raw, item, now)
        ToolGroup.CALENDAR -> CalendarAnswers.render(spec.name, raw, now)
        ToolGroup.FILES -> DriveAnswers.render(spec.name, raw, now)
        ToolGroup.CALLS -> if (spec.connectorId == ToolRegistry.RECORDINGS) RecordingAnswers.render(spec.name, raw, now) else CallAnswers.render(spec.name, raw, now)
        else -> FinanceAnswers.render(spec.name, raw, question, item)
    }
}
