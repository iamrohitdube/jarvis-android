package dev.jarvis.connectors.google

import dev.jarvis.agent.ToolRegistry
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.McpToolDef
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A Google service the user links in Settings (Gmail, Drive). */
interface GoogleService : Connector {
    /** Called after the user approved access in Google's consent screen. */
    suspend fun markLinked()
}

/** One Drive file as the phone keeps it: name, kind and dates only, never the content. */
@Serializable
data class DriveFile(val name: String, val type: String, val modified: String, val link: String = "")

@Serializable
data class DriveResult(val query: String? = null, val files: List<DriveFile> = emptyList())

/** Drive v3 files.list with `drive.metadata.readonly`: names, types and dates, never file content (D29). */
class DriveApi(private val http: OkHttpClient, private val json: Json, private val baseUrl: String = BASE_URL) {
    @Serializable private data class ListResponse(val files: List<Item> = emptyList())
    @Serializable private data class Item(val name: String = "", val mimeType: String = "", val modifiedTime: String = "", val webViewLink: String = "")

    /** Files whose name contains [nameContains] (or the most recent ones), newest first. */
    suspend fun files(token: String, nameContains: String?, max: Int): List<DriveFile> = withContext(Dispatchers.IO) {
        val q = buildString {
            append("trashed = false and mimeType != 'application/vnd.google-apps.folder'")
            nameContains?.let { append(" and name contains '").append(it.replace("\\", "\\\\").replace("'", "\\'")).append("'") }
        }
        val url = "$baseUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", max.coerceIn(1, 50).toString())
            .addQueryParameter("fields", "files(name,mimeType,modifiedTime,webViewLink)")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { r ->
            when {
                r.code == 401 -> throw GmailAuthException()
                !r.isSuccessful -> throw IOException("Drive error ${r.code}")
                else -> json.decodeFromString(ListResponse.serializer(), r.body.string()).files.map {
                    DriveFile(it.name.take(150), kindOf(it.mimeType), it.modifiedTime.take(16), it.webViewLink.take(300))
                }
            }
        }
    }

    companion object {
        const val BASE_URL = "https://www.googleapis.com"

        fun kindOf(mime: String): String = when {
            mime == "application/vnd.google-apps.document" -> "Doc"
            mime == "application/vnd.google-apps.spreadsheet" -> "Sheet"
            mime == "application/vnd.google-apps.presentation" -> "Slides"
            mime == "application/vnd.google-apps.form" -> "Form"
            mime == "application/pdf" -> "PDF"
            mime.startsWith("image/") -> "Image"
            mime.startsWith("video/") -> "Video"
            mime.startsWith("audio/") -> "Audio"
            mime.contains("spreadsheet") || mime.contains("excel") -> "Spreadsheet"
            mime.contains("word") -> "Word"
            mime.contains("presentation") || mime.contains("powerpoint") -> "Presentation"
            mime.startsWith("text/") -> "Text"
            else -> "File"
        }
    }
}

/**
 * Google Drive file search, called by the PHONE (D29 step 4): metadata only, answered on the phone, never sent to
 * Claude. Authorised separately from Gmail ([GoogleAuthorizer] with the Drive scope).
 */
class DriveConnector(
    private val secrets: SecretStore,
    private val tokens: GoogleTokenSource,
    private val api: DriveApi,
    private val json: Json,
    private val audit: AuditSink = AuditSink.NONE,
) : GoogleService {
    override val id = ToolRegistry.DRIVE
    override val displayName = "Google Drive"
    private val needsReauth = MutableStateFlow(false)

    override val status: Flow<ConnectorStatus> = combine(secrets.presence(), needsReauth) { present, reauth ->
        when {
            SecretKey.GOOGLE_DRIVE_LINKED !in present -> ConnectorStatus.NotConnected
            reauth -> ConnectorStatus.NeedsReauth
            else -> ConnectorStatus.Connected
        }
    }

    override suspend fun markLinked() {
        secrets.put(SecretKey.GOOGLE_DRIVE_LINKED, "1")
        needsReauth.value = false
        audit.record(AuditEvent(AuditCategory.CONNECTOR, "sign_in", "ok", detail = id))
    }

    override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
        if (secrets.get(SecretKey.GOOGLE_DRIVE_LINKED) == null) return ConnectorCall.NeedsSignIn
        val query = when (name) {
            RECENT -> null
            SEARCH -> (arguments["query"] as? JsonPrimitive)?.contentOrNull?.let(GmailConnector::cleanQuery)
                ?: return ConnectorCall.Failed("No file name to search for")
            else -> return ConnectorCall.Failed("Unknown Drive tool")
        }
        repeat(2) { attempt ->
            val token = try { tokens.accessToken() } catch (e: Exception) { return ConnectorCall.Failed(e.message ?: "Google Play services error") }
                ?: run { needsReauth.value = true; return ConnectorCall.NeedsSignIn }
            try {
                val files = api.files(token, query, if (query == null) RECENT_COUNT else SEARCH_COUNT)
                needsReauth.value = false
                return ConnectorCall.Ok(json.encodeToString(DriveResult.serializer(), DriveResult(query, files)), toolError = false)
            } catch (_: GmailAuthException) {
                tokens.clearToken(token)
                if (attempt == 1) { needsReauth.value = true; return ConnectorCall.NeedsSignIn }
            } catch (e: Exception) {
                return ConnectorCall.Failed(e.message ?: "Drive couldn't be reached")
            }
        }
        return ConnectorCall.NeedsSignIn
    }

    override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> = emptyList()

    override suspend fun test(): ConnectorTestResult = when (val r = callTool(RECENT, JsonObject(emptyMap()))) {
        is ConnectorCall.Ok -> ConnectorTestResult.Ok
        ConnectorCall.NeedsSignIn -> ConnectorTestResult.Failed("Drive access is missing or expired. Sign in again.", needsReauth = true)
        is ConnectorCall.Failed -> ConnectorTestResult.Failed(r.message, needsReauth = false)
    }

    override suspend fun signOut() {
        secrets.remove(SecretKey.GOOGLE_DRIVE_LINKED)
        needsReauth.value = false
        audit.record(AuditEvent(AuditCategory.CONNECTOR, "sign_out", "ok", detail = id))
    }

    companion object {
        const val RECENT = "drive_recent"
        const val SEARCH = "drive_search"
        const val RECENT_COUNT = 10
        const val SEARCH_COUNT = 15
    }
}
