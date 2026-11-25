package dev.jarvis.agent

import dev.jarvis.connectors.phone.EventTime
import dev.jarvis.connectors.phone.RecordingItem
import dev.jarvis.connectors.phone.RecordingsResult
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Call-recording answers built on the phone from on-phone transcripts (D30). */
object RecordingAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun render(tool: String, raw: String, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(RecordingsResult.serializer(), raw) }.getOrNull()
            ?: return "I couldn't read the recordings."
        val pending = if (r.not_transcribed > 0)
            "\n${r.not_transcribed} newer recording${if (r.not_transcribed == 1) " isn't" else "s aren't"} transcribed yet (Settings → Phone data → Transcribe)." else ""
        if (tool == "recordings_search") {
            val q = r.query.orEmpty()
            if (r.recordings.isEmpty()) return "No transcribed call mentions \"$q\"." + pending
            return (if (r.recordings.size == 1) "1 call mentions \"$q\":\n" else "${r.recordings.size} calls mention \"$q\":\n") +
                r.recordings.joinToString("\n") { "• ${head(it, now)}" + it.snippet.takeIf { s -> s.isNotBlank() }?.let { s -> ": \"$s\"" }.orEmpty() } +
                pending
        }
        if (r.recordings.isEmpty()) return "No transcribed call recordings yet." + pending
        return "Latest call recordings:\n" + r.recordings.joinToString("\n") { "• " + head(it, now) + if (it.status == "failed") " (couldn't transcribe)" else "" } + pending
    }

    private fun head(it: RecordingItem, now: ZonedDateTime): String {
        val who = it.name.ifBlank { "Unknown caller" }
        val t = EventTime.dateTime(it.at)
        val whenText = t?.let {
            when (ChronoUnit.DAYS.between(it.toLocalDate(), now.toLocalDate())) {
                0L -> "today " + it.format(TIME)
                1L -> "yesterday " + it.format(TIME)
                else -> it.format(DAY)
            }
        } ?: it.at
        val mins = if (it.duration_s >= 60) " (${it.duration_s / 60} min)" else ""
        return "$who, $whenText$mins"
    }
}
