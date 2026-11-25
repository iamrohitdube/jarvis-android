package dev.jarvis.agent

import dev.jarvis.connectors.google.DriveFile
import dev.jarvis.connectors.google.DriveResult
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZonedDateTime

/** Drive answers built on the phone (D29): file names, kinds and when they were last edited. */
object DriveAnswers {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SHOW = 8

    fun render(tool: String, raw: String, now: ZonedDateTime): String {
        val r = runCatching { json.decodeFromString(DriveResult.serializer(), raw) }.getOrNull()
            ?: return "I couldn't read the Drive result."
        if (tool == "drive_search") {
            val q = r.query.orEmpty()
            if (r.files.isEmpty()) return "No Drive files named like \"$q\"."
            return (if (r.files.size == 1) "1 file for \"$q\":\n" else "${r.files.size} files for \"$q\":\n") + lines(r.files, now)
        }
        if (r.files.isEmpty()) return "Your Drive has no recent files."
        return "Recently edited in Drive:\n" + lines(r.files, now)
    }

    private fun lines(files: List<DriveFile>, now: ZonedDateTime) =
        files.take(SHOW).joinToString("\n") { f -> "• ${f.name} (${f.type}, edited ${edited(f.modified, now)})" } +
            if (files.size > SHOW) "\n…and ${files.size - SHOW} more." else ""

    private fun edited(iso: String, now: ZonedDateTime): String = runCatching {
        val ms = Instant.parse(if (iso.length == 16) "$iso:00Z" else iso).toEpochMilli()
        EmailAnswers.whenLabel(ms, now)
    }.getOrDefault(iso.take(10))
}
