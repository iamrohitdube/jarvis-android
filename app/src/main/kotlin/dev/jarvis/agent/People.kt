package dev.jarvis.agent

import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.McpToolDef
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.google.InboxResult
import dev.jarvis.connectors.phone.CalendarResult
import dev.jarvis.connectors.phone.CallLogResult
import dev.jarvis.connectors.phone.EventTime
import dev.jarvis.messaging.MessagesResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Prep me for my call with Priya", "catch me up on Ravi", "what's new with Mom": a person brief (Phase 15.1, D31).
 * Recognised on the phone; the brief itself still goes through the security gate like any personal read.
 */
object PersonIntent {
    private val PATTERN = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:jarvis[,]?\\s+)?(?:please\\s+|can you\\s+)?" +
            "(?:prep me for (?:my |the )?(?:call|meeting|chat|catch[- ]?up) with|brief me on|brief me about|catch me up on|" +
            "catch me up with|what'?s (?:new|up|happening) with|what'?s the latest (?:with|from)|tell me about my (?:chats|conversations) with|" +
            "everything (?:from|with)|show me|people:)\\s+(.+?)[.!?]*$",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_A_PERSON = setOf(
        "me", "you", "it", "that", "this", "today", "the weather", "my day", "my calendar", "my email", "my emails",
        "the news", "the market", "my portfolio", "my money", "my schedule", "my messages", "my notifications",
    )

    fun target(text: String): String? {
        val m = PATTERN.find(text.trim()) ?: return null
        val raw = m.groupValues[1].trim()
        if (raw.lowercase(Locale.ENGLISH) in NOT_A_PERSON) return null
        val who = raw.removePrefix("my ").trim()
        val lower = who.lowercase(Locale.ENGLISH)
        // "show me" is too broad on its own: only "show me <name>" with a short, name-like target.
        if (text.trim().lowercase(Locale.ENGLISH).let { it.startsWith("show me") } && (who.split(' ').size > 3 || who.any { it.isDigit() })) return null
        if (who.isEmpty() || lower in NOT_A_PERSON || text.lowercase(Locale.ENGLISH).startsWith("show me my") || who.length > 60) return null
        return who
    }
}

/** Everything about one person, gathered on the phone from each source the user allowed. */
@Serializable
data class PersonBrief(
    val name: String,
    val calls: CallLogResult? = null,
    val texts: MessagesResult? = null,
    val chats: MessagesResult? = null,
    val emails: InboxResult? = null,
    val events: CalendarResult? = null,
    /** Sources that couldn't be read (not allowed or not signed in): said once at the end. */
    val unavailable: List<String> = emptyList(),
)

/** One recent person for the People screen. */
data class RecentPerson(val name: String, val lastAt: String, val via: Set<String>)

/**
 * The person brief (Phase 15.1): asks each phone source about one person, in parallel, and keeps only what mentions
 * them. Never sent to Claude (not a consent feature). A source that isn't allowed is skipped and named.
 */
class PeopleConnector(
    private val callLog: Connector?,
    private val sms: Connector?,
    private val chats: Connector?,
    private val gmail: Connector?,
    private val calendar: Connector?,
    private val json: Json,
) : Connector {
    override val id = ToolRegistry.PEOPLE
    override val displayName = "People"
    override val status: Flow<ConnectorStatus> = flowOf(ConnectorStatus.Connected)

    override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> = emptyList()
    override suspend fun test(): ConnectorTestResult = ConnectorTestResult.Ok
    override suspend fun signOut() = Unit

    override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
        if (name == MEETING) {
            val prep = nextMeetings(java.time.LocalDateTime.now(), withinMinutes = 24 * 60).firstOrNull() ?: MeetingPrepResult()
            return ConnectorCall.Ok(json.encodeToString(MeetingPrepResult.serializer(), prep), toolError = false)
        }
        val who = (arguments["query"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
            ?: return ConnectorCall.Failed("Who should I look up?")
        return ConnectorCall.Ok(json.encodeToString(PersonBrief.serializer(), brief(who)), toolError = false)
    }

    suspend fun brief(who: String): PersonBrief = coroutineScope {
        val args = JsonObject(mapOf("query" to JsonPrimitive(who)))
        val missing = java.util.concurrent.ConcurrentLinkedQueue<String>()
        suspend fun read(c: Connector?, tool: String, label: String): String? {
            if (c == null) return null
            return when (val r = runCatching { c.callTool(tool, args) }.getOrNull()) {
                is ConnectorCall.Ok -> r.text.takeIf { !r.toolError }
                null, is ConnectorCall.Failed, ConnectorCall.NeedsSignIn -> { missing += label; null }
            }
        }
        val calls = async { read(callLog, "calls_with", "call log") }
        val texts = async { read(sms, "sms_from", "texts") }
        val chatMsgs = async { read(chats, "chats_from", "chat messages") }
        val mail = async { read(gmail, "gmail_search", "Gmail") }
        val events = async { read(calendar, "calendar_search", "calendar") }
        val words = who.lowercase(Locale.ENGLISH).split(Regex("\\s+")).filter { it.isNotBlank() }
        PersonBrief(
            name = who,
            calls = calls.await()?.let { decode(CallLogResult.serializer(), it) },
            texts = texts.await()?.let { decode(MessagesResult.serializer(), it) },
            chats = chatMsgs.await()?.let { decode(MessagesResult.serializer(), it) },
            // Gmail's search also matches emails that only mention the name: keep the ones FROM them.
            emails = mail.await()?.let { decode(InboxResult.serializer(), it) }?.let { r ->
                r.copy(messages = r.messages.filter { e -> words.all { e.from.lowercase(Locale.ENGLISH).contains(it) } })
            },
            events = events.await()?.let { decode(CalendarResult.serializer(), it) },
            unavailable = missing.toList().sorted(),
        )
    }

    /** People you were in touch with lately (calls, texts, chats), newest first, for the People screen. */
    suspend fun recent(limit: Int = 30): List<RecentPerson> = coroutineScope {
        val empty = JsonObject(emptyMap())
        suspend fun ok(c: Connector?, tool: String) = (runCatching { c?.callTool(tool, empty) }.getOrNull() as? ConnectorCall.Ok)?.text
        val calls = async { ok(callLog, "calls_recent")?.let { decode(CallLogResult.serializer(), it) } }
        val texts = async { ok(sms, "sms_recent")?.let { decode(MessagesResult.serializer(), it) } }
        val chatMsgs = async { ok(chats, "chats_recent")?.let { decode(MessagesResult.serializer(), it) } }
        val seen = LinkedHashMap<String, RecentPerson>()
        fun add(name: String, at: String, via: String) {
            if (name.isBlank() || name.startsWith("number ending") || name.any { it.isDigit() } && name.none { it.isLetter() }) return
            val key = name.lowercase(Locale.ENGLISH)
            val old = seen[key]
            seen[key] = if (old == null) RecentPerson(name, at, setOf(via))
            else old.copy(lastAt = maxOf(old.lastAt, at), via = old.via + via)
        }
        calls.await()?.calls?.forEach { add(it.name, it.at, "calls") }
        texts.await()?.messages?.forEach { add(it.from, it.at, "texts") }
        chatMsgs.await()?.messages?.forEach { add(it.conversation.ifBlank { it.from }, it.at, it.app) }
        seen.values.sortedByDescending { it.lastAt }.take(limit)
    }

    /**
     * Upcoming timed meetings within [withinMinutes] (next 7 days at most), each with briefs on the people named in
     * its title (Phase 15.2). [only] limits it to those meetings (the card job passes the due ones).
     */
    suspend fun nextMeetings(
        now: java.time.LocalDateTime, withinMinutes: Long, only: (List<dev.jarvis.connectors.phone.CalendarEvent>) -> List<dev.jarvis.connectors.phone.CalendarEvent> = { it },
    ): List<MeetingPrepResult> {
        val raw = (runCatching { calendar?.callTool("calendar_upcoming", JsonObject(emptyMap())) }.getOrNull() as? ConnectorCall.Ok)?.text ?: return emptyList()
        val events = decode(CalendarResult.serializer(), raw)?.events.orEmpty().filter { e ->
            val start = EventTime.dateTime(e.start)
            !e.all_day && start != null && start.isAfter(now.minusMinutes(5)) && java.time.Duration.between(now, start).toMinutes() <= withinMinutes
        }
        return only(events).map { e -> MeetingPrepResult(e, MeetingPrep.names(e.title).map { brief(it) }) }
    }

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, raw: String): T? = runCatching { json.decodeFromString(s, raw) }.getOrNull()

    companion object {
        const val BRIEF = "person_brief"
        const val MEETING = "meeting_prep"
    }
}

/** The person brief as text, made on the phone: one line per source, most useful first. Data, never instructions. */
object PeopleAnswers {
    private val json = Json { ignoreUnknownKeys = true }

    /** "Next: "1:1 with Priya" today 3:00 PM at Room 4." then a short brief per person in it (Phase 15.2). */
    fun renderMeeting(raw: String, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(MeetingPrepResult.serializer(), raw) }.getOrNull() ?: return "I couldn't check your calendar."
        val e = r.event ?: return "No meetings in the next 24 hours."
        val start = EventTime.dateTime(e.start)
        val head = "Next: \"${clip(e.title, 70)}\" " + (start?.let { label(it, now) } ?: e.start) +
            (if (e.location.isNotBlank()) " at ${clip(e.location, 40)}" else "") + "."
        if (r.people.isEmpty()) return "$head\nNo one named in the title, so no people brief."
        return head + "\n" + r.people.joinToString("\n") { b -> section(b, now, skipMeeting = true) }
    }

    /** One person's lines for a meeting card or brief. */
    fun section(b: PersonBrief, now: ZonedDateTime, skipMeeting: Boolean = false): String {
        val lines = listOfNotNull(if (skipMeeting) null else meeting(b, now), calls(b, now), messages(b.chats, now), messages(b.texts, now), email(b, now))
        return if (lines.isEmpty()) "${b.realName()}: nothing recent." else "${b.realName()}:\n" + lines.joinToString("\n") { "• $it" }
    }

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun render(raw: String, now: ZonedDateTime): String {
        val b = runCatching { json.decodeFromString(PersonBrief.serializer(), raw) }.getOrNull() ?: return "I couldn't put that together."
        val lines = listOfNotNull(meeting(b, now), calls(b, now), messages(b.chats, now), messages(b.texts, now), email(b, now))
        val head = b.realName()
        if (lines.isEmpty()) {
            return "Nothing recent with ${b.name}." + missing(b)
        }
        return "$head:\n" + lines.joinToString("\n") { "• $it" } + missing(b)
    }

    /** The contact's own name when a source knows it ("Priya Sharma" for "priya"). */
    private fun PersonBrief.realName(): String =
        calls?.calls?.firstOrNull()?.name?.takeIf { it.isNotBlank() && !it.startsWith("number ending") }
            ?: texts?.messages?.firstOrNull()?.from?.takeIf { it.any(Char::isLetter) }
            ?: name.replaceFirstChar { it.titlecase(Locale.ENGLISH) }

    private fun missing(b: PersonBrief) =
        if (b.unavailable.isEmpty()) "" else "\n(Couldn't check: ${b.unavailable.joinToString(", ")}.)"

    private fun calls(b: PersonBrief, now: ZonedDateTime): String? {
        val list = b.calls?.calls ?: return null
        if (list.isEmpty()) return null
        val talked = list.firstOrNull { it.type in setOf("incoming", "outgoing") && it.duration_s > 0 }
        val missed = list.filter { it.type == "missed" && (talked == null || it.at > talked.at) }
        val parts = mutableListOf<String>()
        if (missed.isNotEmpty()) parts += "${missed.size} missed call" + (if (missed.size > 1) "s" else "") + ", latest ${label(missed.first().at, now)}"
        talked?.let { parts += "last talked ${label(it.at, now)} (${minutes(it.duration_s)})" }
        if (parts.isEmpty()) parts += "last call ${label(list.first().at, now)} (${list.first().type})"
        return "Calls: " + parts.joinToString("; ")
    }

    private fun messages(r: MessagesResult?, now: ZonedDateTime): String? {
        val list = r?.messages?.takeIf { it.isNotEmpty() } ?: return null
        val m = list.first()
        val what = if (r.source == "sms") "Texts" else m.app
        val count = if (list.size > 1) "${list.size} recent, latest" else "latest"
        return "$what: $count ${label(m.at, now)}: \"${clip(m.text, 90)}\""
    }

    private fun email(b: PersonBrief, now: ZonedDateTime): String? {
        val list = b.emails?.messages?.takeIf { it.isNotEmpty() } ?: return null
        val e = list.first()
        val unread = list.count { it.unread }
        val at = java.time.Instant.ofEpochMilli(e.at).atZone(now.zone).toLocalDateTime()
        return "Email: " + (if (unread > 0) "$unread unread; " else "") + "latest \"${clip(e.subject, 70)}\" ${label(at, now)}"
    }

    /** The next meeting that mentions them (or the latest past one). */
    private fun meeting(b: PersonBrief, now: ZonedDateTime): String? {
        val events = b.events?.events?.takeIf { it.isNotEmpty() } ?: return null
        val nowLocal = now.toLocalDateTime()
        val upcoming = events.filter { (EventTime.dateTime(it.start) ?: EventTime.date(it.start)?.atStartOfDay()) ?.isAfter(nowLocal.minusMinutes(30)) == true }
            .minByOrNull { it.start }
        val e = upcoming ?: return null
        val start = EventTime.dateTime(e.start)
        val whenText = if (e.all_day || start == null) EventTime.date(e.start)?.format(DAY) ?: e.start else label(start, now)
        return "Next meeting: \"${clip(e.title, 60)}\" $whenText" + (if (e.location.isNotBlank()) " at ${clip(e.location, 40)}" else "")
    }

    private fun label(at: String, now: ZonedDateTime): String = EventTime.dateTime(at)?.let { label(it, now) } ?: at

    private fun label(t: LocalDateTime, now: ZonedDateTime): String =
        when (ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())) {
            0L -> "today " + t.format(TIME)
            -1L -> "yesterday " + t.format(TIME)
            1L -> "tomorrow " + t.format(TIME)
            else -> t.format(DAY) + " " + t.format(TIME)
        }

    private fun minutes(s: Long) = if (s < 60) "${s}s" else "${s / 60} min"

    private fun clip(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max - 1) + "…" else it }
}
