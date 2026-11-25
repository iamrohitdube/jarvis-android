package dev.jarvis.connectors.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Gmail said the token is no good (401): the caller clears it and asks Google Play services for a new one. */
class GmailAuthException : IOException("Gmail rejected the access token")

/**
 * One email as the phone keeps it: headers, Gmail's short snippet and labels, and the triage result. Never the
 * body or attachments (a bill's body is read once to pull out [amount] and [due], then dropped).
 */
@Serializable
data class EmailHeader(
    val from: String,
    val subject: String,
    /** Received time, epoch ms (Gmail's internalDate). */
    val at: Long,
    val unread: Boolean,
    val snippet: String = "",
    /** Gmail label ids (IMPORTANT, STARRED, CATEGORY_SOCIAL, ...): triage signals. */
    val labels: List<String> = emptyList(),
    /** Bulk mail: has a List-Unsubscribe header or Precedence bulk/list. */
    val bulk: Boolean = false,
    /** [EmailTriage.Kind] name, set by the connector. */
    val kind: String? = null,
    /** Bills and statements: amount due and due date as found in the email. */
    val amount: String? = null,
    val due: String? = null,
    /** Gmail message id (to read a bill's amount once). */
    val id: String = "",
)

/**
 * Gmail REST v1, read-only (`gmail.readonly`, D29). Only `format=metadata` with From/Subject/Date is requested, so
 * message bodies and attachments are never downloaded.
 */
class GmailApi(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = BASE_URL,
) {
    @Serializable private data class ListResponse(val messages: List<Ref> = emptyList(), val resultSizeEstimate: Int = 0)
    @Serializable private data class Ref(val id: String)
    @Serializable private data class Message(
        val id: String = "",
        val labelIds: List<String> = emptyList(),
        val snippet: String = "",
        val internalDate: String = "0",
        val payload: Payload = Payload(),
    )
    @Serializable private data class Payload(
        val mimeType: String = "",
        val headers: List<Header> = emptyList(),
        val body: Body = Body(),
        val parts: List<Payload> = emptyList(),
    )
    @Serializable private data class Body(val data: String? = null)
    @Serializable private data class Header(val name: String = "", val value: String = "")

    data class Page(val estimate: Int, val messages: List<EmailHeader>)

    /** Messages matching [query] (Gmail search syntax), newest first, with headers and snippets. */
    suspend fun search(token: String, query: String, max: Int): Page {
        val url = "$baseUrl/gmail/v1/users/me/messages".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", max.coerceIn(1, MAX_RESULTS).toString())
            .build()
        val list = json.decodeFromString(ListResponse.serializer(), get(token, url))
        // A handful of small metadata reads, in parallel.
        val headers = coroutineScope {
            list.messages.take(max).map { ref -> async { metadata(token, ref.id) } }.awaitAll()
        }
        return Page(list.resultSizeEstimate, headers.sortedByDescending { it.at })
    }

    private suspend fun metadata(token: String, id: String): EmailHeader {
        val url = "$baseUrl/gmail/v1/users/me/messages".toHttpUrl().newBuilder()
            .addPathSegment(id)
            .addQueryParameter("format", "metadata")
            .apply { METADATA_HEADERS.forEach { addQueryParameter("metadataHeaders", it) } }
            .build()
        val m = json.decodeFromString(Message.serializer(), get(token, url))
        fun header(name: String) = m.payload.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()
        val precedence = header("Precedence").lowercase()
        return EmailHeader(
            id = m.id.ifBlank { id },
            from = header("From").take(MAX_FIELD),
            subject = header("Subject").take(MAX_FIELD),
            at = m.internalDate.toLongOrNull() ?: 0L,
            unread = "UNREAD" in m.labelIds,
            snippet = m.snippet.take(MAX_SNIPPET),
            labels = m.labelIds.filter { it in KEPT_LABELS },
            bulk = header("List-Unsubscribe").isNotBlank() || precedence == "bulk" || precedence == "list",
        )
    }

    /**
     * Plain text of one message, read on the phone only to pull out a bill's amount and due date; the caller keeps
     * the extracted values, never this text. HTML is reduced to text. Clipped to [MAX_BODY] characters.
     */
    suspend fun bodyText(token: String, id: String): String {
        val url = "$baseUrl/gmail/v1/users/me/messages".toHttpUrl().newBuilder()
            .addPathSegment(id)
            .addQueryParameter("format", "full")
            .build()
        val m = json.decodeFromString(Message.serializer(), get(token, url))
        fun collect(p: Payload, mime: String): List<String> =
            (if (p.mimeType.equals(mime, ignoreCase = true)) listOfNotNull(p.body.data?.let(::decode)) else emptyList()) +
                p.parts.flatMap { collect(it, mime) }
        val text = collect(m.payload, "text/plain").joinToString("\n").ifBlank {
            collect(m.payload, "text/html").joinToString("\n").let(::htmlToText)
        }
        return text.replace(Regex("\\s+"), " ").take(MAX_BODY)
    }

    private fun decode(data: String): String =
        runCatching { String(java.util.Base64.getUrlDecoder().decode(data.trimEnd('=')), Charsets.UTF_8) }.getOrDefault("")

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#8377;", "₹").replace("&#x20b9;", "₹")
    private suspend fun get(token: String, url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { r ->
            when {
                r.code == 401 -> throw GmailAuthException()
                !r.isSuccessful -> throw IOException("Gmail error ${r.code}")
                else -> r.body.string()
            }
        }
    }

    companion object {
        const val BASE_URL = "https://gmail.googleapis.com"
        const val MAX_RESULTS = 25
        private const val MAX_FIELD = 200
        private const val MAX_SNIPPET = 240
        private const val MAX_BODY = 8_000
        val METADATA_HEADERS = listOf("From", "Subject", "List-Unsubscribe", "Precedence")
        /** Only the labels triage uses are kept (user-made label names never leave Gmail's ids anyway). */
        private val KEPT_LABELS = setOf(
            "IMPORTANT", "STARRED", "CATEGORY_PERSONAL", "CATEGORY_SOCIAL", "CATEGORY_FORUMS",
            "CATEGORY_UPDATES", "CATEGORY_PROMOTIONS",
        )
    }
}
