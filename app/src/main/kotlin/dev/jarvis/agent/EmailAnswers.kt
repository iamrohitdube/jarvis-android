package dev.jarvis.agent

import dev.jarvis.connectors.google.EmailHeader
import dev.jarvis.connectors.google.InboxResult
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Email answers built ON THE PHONE from the Gmail connector's filtered headers (D29). No LLM: short, voice-friendly
 * templates. Sender names are shown without email addresses.
 */
object EmailAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SHOW = 5

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
        if (r.messages.isEmpty()) return head + hiddenNote(r.hidden)
        return head + " Latest:\n" + lines(r.messages, now) + hiddenNote(r.hidden)
    }

    private fun recent(r: InboxResult, now: ZonedDateTime): String {
        if (r.messages.isEmpty()) return "No emails to show." + hiddenNote(r.hidden)
        return "Latest emails:\n" + lines(r.messages, now) + hiddenNote(r.hidden)
    }

    private fun search(messages: List<EmailHeader>, query: String, hidden: Int, now: ZonedDateTime): String {
        val q = query.removePrefix("from:").trim()
        if (messages.isEmpty()) return "No recent emails found for \"$q\"." + hiddenNote(hidden)
        val n = messages.size
        return (if (n == 1) "1 email for \"$q\":\n" else "$n emails for \"$q\":\n") + lines(messages, now) + hiddenNote(hidden)
    }

    private fun lines(messages: List<EmailHeader>, now: ZonedDateTime): String =
        messages.take(SHOW).joinToString("\n") { e ->
            val subject = e.subject.ifBlank { "(no subject)" }
            "• ${senderName(e.from)}: $subject (${whenLabel(e.at, now)})"
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
        else -> FinanceAnswers.render(spec.name, raw, question, item)
    }
}
