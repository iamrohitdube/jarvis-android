package dev.jarvis.connectors.phone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.data.db.TranscriptDao
import dev.jarvis.data.db.TranscriptEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A recording file the phone's dialer saved (JARVIS never records calls: Android blocks it, D30). */
data class RecordingFile(val uri: String, val fileName: String, val modifiedMs: Long, val sizeBytes: Long)

/** Lists recordings in the folder the user picked (Storage Access Framework, read-only grant). */
fun interface RecordingStore {
    fun list(): List<RecordingFile>
}

/** Turns one recording into text on the phone. Null result = models missing. */
fun interface RecordingTranscriber {
    /** Returns the transcript and the duration in ms, or null when the speech models aren't downloaded. */
    suspend fun transcribe(uri: String): Pair<String, Long>?
}

object RecordingNames {
    private val NOISE = Regex("(?i)\\b(call|recording|record|rec|voice|audio|incoming|outgoing|in|out)\\b")

    /** "Call recording Mom_251125_1005.m4a" -> "Mom"; "+915550001001_20261125.amr" -> "+915550001001"; digits only -> "". */
    fun personOf(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        Regex("\\+?\\d[\\d ]{8,}\\d").find(base)?.let { m ->
            val letters = base.replace(m.value, " ").replace(Regex("[^\\p{L} ]"), " ").replace(NOISE, " ").trim()
            return letters.replace(Regex("\\s+"), " ").ifEmpty { m.value.replace(" ", "") }
        }
        return base.replace(Regex("[\\d_\\-.()\\[\\]@]+"), " ").replace(NOISE, " ").replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Transcribes new recordings on the phone and keeps the text in the local database (D30). One run handles at
 * most [maxFiles] files so it can run while the user waits; progress is shown in Settings.
 */
class RecordingIndexer(
    private val store: suspend () -> RecordingStore?,
    private val dao: TranscriptDao,
    private val transcriber: RecordingTranscriber,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Progress(val done: Int, val total: Int, val current: String?)
    data class Summary(val transcribed: Int, val failed: Int, val remaining: Int, val noModels: Boolean)

    private val mutex = Mutex()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    /** Files in the folder that have no transcript yet. */
    suspend fun pending(): List<RecordingFile> {
        val files = store()?.list() ?: return emptyList()
        val known = dao.knownUris().toSet()
        return files.filter { it.uri !in known }.sortedByDescending { it.modifiedMs }
    }

    suspend fun run(maxFiles: Int = 10): Summary = mutex.withLock {
        val todo = pending()
        val batch = todo.take(maxFiles)
        var ok = 0
        var failed = 0
        try {
            for ((i, f) in batch.withIndex()) {
                _progress.value = Progress(i, batch.size, RecordingNames.personOf(f.fileName).ifEmpty { "unknown caller" })
                val result = try {
                    transcriber.transcribe(f.uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                    dao.upsert(entity(f, "", 0, "failed"))
                    continue
                } ?: return@withLock Summary(ok, failed, todo.size - ok - failed, noModels = true)
                dao.upsert(entity(f, result.first.trim(), result.second, "done"))
                ok++
            }
        } finally {
            _progress.value = null
        }
        Summary(ok, failed, todo.size - ok - failed, noModels = false)
    }

    private fun entity(f: RecordingFile, text: String, durationMs: Long, status: String) = TranscriptEntity(
        uri = f.uri, name = RecordingNames.personOf(f.fileName), recordedAt = f.modifiedMs,
        durationMs = durationMs, text = text.take(MAX_TEXT), status = status, updatedAt = clock(),
    )

    companion object {
        /** ~1 hour of speech. */
        const val MAX_TEXT = 60_000
    }
}

@Serializable
data class RecordingItem(val name: String, val at: String, val duration_s: Long, val status: String, val snippet: String = "")

@Serializable
data class RecordingsResult(val query: String? = null, val recordings: List<RecordingItem> = emptyList(), val not_transcribed: Int = 0)

/**
 * Call recordings (D30): searches the on-phone transcripts. Transcript text is shown on the phone only and never
 * sent to Claude (not even to recipes).
 */
class RecordingsConnector(
    private val dao: TranscriptDao,
    private val indexer: RecordingIndexer,
    private val json: Json,
    granted: () -> Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : LocalConnector(ToolRegistry.RECORDINGS, "Call recordings", granted, emptyList(),
    "Search what was said in calls your phone's dialer recorded. Transcribed on the phone.") {

    override suspend fun read(tool: String, arguments: JsonObject): String {
        val pending = indexer.pending().size
        val result = when (tool) {
            RECENT -> RecordingsResult(recordings = dao.recent(10).map { item(it, null) }, not_transcribed = pending)
            SEARCH -> {
                val q = arguments.text("query") ?: throw IllegalArgumentException("Nothing to search for")
                val words = q.lowercase(Locale.ENGLISH).split(Regex("\\s+")).filter { it.length > 1 }
                val hits = dao.recent(2000).filter { t ->
                    val hay = (t.name + " " + t.text).lowercase(Locale.ENGLISH)
                    words.isNotEmpty() && words.all { hay.contains(it) }
                }
                RecordingsResult(q, hits.take(10).map { item(it, words) }, pending)
            }
            else -> throw IllegalArgumentException("Unknown recordings tool")
        }
        return json.encodeToString(RecordingsResult.serializer(), result)
    }

    private fun item(t: TranscriptEntity, words: List<String>?): RecordingItem = RecordingItem(
        name = t.name,
        at = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.recordedAt), zone()).format(LOCAL),
        duration_s = t.durationMs / 1000,
        status = t.status,
        snippet = words?.let { snippet(t.text, it) }.orEmpty(),
    )

    companion object {
        const val RECENT = "recordings_recent"
        const val SEARCH = "recordings_search"
        private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /** ~160 characters around the first match. */
        fun snippet(text: String, words: List<String>): String {
            val lower = text.lowercase(Locale.ENGLISH)
            val at = words.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return ""
            val start = (at - 70).coerceAtLeast(0)
            val end = (at + 90).coerceAtMost(text.length)
            return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
        }
    }
}

/** The folder the user picked, through the Storage Access Framework (persisted read grant). */
class SafRecordingStore(private val context: Context, private val treeUri: Uri) : RecordingStore {
    override fun list(): List<RecordingFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<RecordingFile>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2).orEmpty()
                val name = c.getString(1).orEmpty()
                if (!mime.startsWith("audio/") && !Regex("(?i)\\.(m4a|mp3|amr|aac|ogg|opus|wav|3gp)$").containsMatchIn(name)) continue
                out += RecordingFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)).toString(),
                    fileName = name, modifiedMs = c.getLong(3), sizeBytes = c.getLong(4),
                )
            }
        }
        return out
    }

    companion object {
        /** Whether the app still holds the read grant for [uri] (lost if the folder moves or the grant is removed). */
        fun hasGrant(context: Context, uri: String): Boolean = uri.isNotBlank() &&
            context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
    }
}
