package dev.jarvis.agent

import dev.jarvis.connectors.phone.CalendarEvent
import dev.jarvis.connectors.phone.EventTime
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/** One upcoming meeting with a brief on each person in it (Phase 15.2). */
@Serializable
data class MeetingPrepResult(val event: CalendarEvent? = null, val people: List<PersonBrief> = emptyList())

/**
 * Meeting prep (Phase 15.2, D38): who a meeting is with (from its title), which meetings are due for a card, and the
 * voice phrases. Pure, so it is unit-tested.
 */
object MeetingPrep {
    /** Cards go out for meetings starting within this many minutes (the check runs every ~15 min). */
    const val LEAD_MINUTES = 35L
    const val MAX_PEOPLE = 3

    private val VOICE = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:jarvis[,]?\\s+)?(?:please\\s+)?" +
            "(?:prep|prepare|brief) me (?:for|on|about) (?:my |the )?(?:next|upcoming|coming) (?:meeting|call)[.!?]*$" +
            "|^what'?s my next meeting about[.!?]*$",
        RegexOption.IGNORE_CASE,
    )

    fun wantsNextMeeting(text: String) = VOICE.matches(text.trim())

    /** Words that name the meeting, not a person ("Weekly sync", "Team standup"). */
    private val NOT_A_NAME = setOf(
        "team", "standup", "stand-up", "sync", "meeting", "call", "review", "weekly", "daily", "monthly", "catch", "up",
        "catchup", "catch-up", "1:1", "1-1", "one", "on", "intro", "interview", "lunch", "coffee", "dinner", "demo",
        "planning", "retro", "all", "hands", "the", "a", "an", "and", "for", "about", "re", "of", "to", "in", "me", "my",
        "zoom", "teams", "meet", "google", "chat", "discussion", "kickoff", "kick-off", "check-in", "checkin", "qbr",
        "project", "update", "session", "workshop", "training", "x", "vs", "office", "hours",
    )

    /**
     * People in a meeting title: "1:1 with Priya", "Call with Ravi Kumar re budget", "Priya / Rohit sync",
     * "Amit <> Neha", "Coffee with Sam & Alex". Up to [MAX_PEOPLE]; empty when the title names nobody.
     */
    fun names(title: String): List<String> {
        val t = title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ").trim()
        val after = Regex("\\bwith\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)
        // Without "with", only titles that pair people up ("Priya / Rohit sync", "Amit <> Neha") name anyone.
        if (after == null && !Regex("\\s(/|<>|&|\\+|x|vs\\.?)\\s", RegexOption.IGNORE_CASE).containsMatchIn(t)) return emptyList()
        val candidates = (after ?: t)
            .split(Regex("\\s*(?:/|<>|&|\\+|,|\\band\\b|\\bx\\b|\\bvs\\.?|:|-|–|\\|)\\s*", RegexOption.IGNORE_CASE))
            .map { part ->
                // Drop trailing topic words: "Ravi Kumar re budget" -> "Ravi Kumar".
                part.trim().split(Regex("\\s+"))
                    .takeWhile { w -> w.lowercase(Locale.ENGLISH).trim('.', '\'') !in setOf("re", "about", "for", "on", "to") }
                    .filter { w -> w.lowercase(Locale.ENGLISH).trim('.', '\'') !in NOT_A_NAME }
                    .joinToString(" ")
            }
            .filter { it.isNotBlank() && it.first().isLetter() && it.split(' ').size <= 3 && it.none(Char::isDigit) }
        // Without "with", a title must look like names (capitalised words) to count.
        val people = if (after != null) candidates else candidates.filter { c -> c.split(' ').all { it.first().isUpperCase() } }
        return people.distinctBy { it.lowercase(Locale.ENGLISH) }.take(MAX_PEOPLE)
    }

    /** Stable id for "already sent a card for this meeting" (hashed: no titles are stored). */
    fun key(e: CalendarEvent) = (e.title + "|" + e.start).hashCode().toString(16)

    /** Timed (not all-day) meetings starting within [LEAD_MINUTES] that haven't had a card yet. */
    fun due(events: List<CalendarEvent>, now: LocalDateTime, sent: Set<String>): List<CalendarEvent> = events.filter { e ->
        if (e.all_day || key(e) in sent) return@filter false
        val start = EventTime.dateTime(e.start) ?: return@filter false
        val minutes = Duration.between(now, start).toMinutes()
        start.isAfter(now) && minutes <= LEAD_MINUTES
    }

    /** "in 25 min" / "now". */
    fun startsIn(e: CalendarEvent, now: LocalDateTime): String {
        val start = EventTime.dateTime(e.start) ?: return ""
        val m = Duration.between(now, start).toMinutes()
        return if (m <= 1) "now" else "in $m min"
    }
}
