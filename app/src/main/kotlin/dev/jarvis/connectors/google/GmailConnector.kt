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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What a Gmail tool returns to the agent (and what is cached): filtered headers, never bodies. */
@Serializable
data class InboxResult(
    /** Gmail's estimate of matching messages (unread count for gmail_unread), before the privacy filter. */
    val estimate: Int? = null,
    val query: String? = null,
    val messages: List<EmailHeader> = emptyList(),
    /** How many fetched messages the privacy filter hid (codes, excluded senders). */
    val hidden: Int = 0,
)

/**
 * Gmail, called by the PHONE (D29): read-only, headers and snippets only, privacy-filtered before anything is
 * cached or shown. Its results are answered on the phone and never sent to Claude. The access token comes from
 * Google Play services per call and goes only to gmail.googleapis.com.
 */
class GmailConnector(
    private val secrets: SecretStore,
    private val tokens: GoogleTokenSource,
    private val api: GmailApi,
    private val json: Json,
    /** The user's excluded senders (Settings); banks by default. */
    private val excludedSenders: suspend () -> List<String> = { EmailFilter.DEFAULT_EXCLUDED_SENDERS },
    private val audit: AuditSink = AuditSink.NONE,
) : GoogleService {
    override val id = ToolRegistry.GMAIL
    override val displayName = "Gmail"

    private val needsReauth = MutableStateFlow(false)

    override val status: Flow<ConnectorStatus> = combine(secrets.presence(), needsReauth) { present, reauth ->
        when {
            SecretKey.GOOGLE_LINKED !in present -> ConnectorStatus.NotConnected
            reauth -> ConnectorStatus.NeedsReauth
            else -> ConnectorStatus.Connected
        }
    }

    override suspend fun markLinked() {
        secrets.put(SecretKey.GOOGLE_LINKED, "1")
        needsReauth.value = false
        audit.record(AuditEvent(AuditCategory.CONNECTOR, "sign_in", "ok", detail = id))
    }

    override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
        if (secrets.get(SecretKey.GOOGLE_LINKED) == null) return ConnectorCall.NeedsSignIn
        val query = when (name) {
            UNREAD -> "in:inbox is:unread ${EmailFilter.PROMOTIONS_QUERY}"
            RECENT -> "in:inbox ${EmailFilter.PROMOTIONS_QUERY}"
            SEARCH -> {
                val q = (arguments["query"] as? JsonPrimitive)?.contentOrNull?.let(::cleanQuery)
                    ?: return ConnectorCall.Failed("No sender or topic to search for")
                "$q ${EmailFilter.PROMOTIONS_QUERY}"
            }
            else -> return ConnectorCall.Failed("Unknown Gmail tool")
        }
        return withToken { token ->
            val page = api.search(token, query, if (name == SEARCH) FETCH_SEARCH else FETCH)
            val kept = EmailFilter.apply(page.messages, excludedSenders())
            val result = InboxResult(
                estimate = page.estimate,
                query = if (name == SEARCH) query.removeSuffix(" ${EmailFilter.PROMOTIONS_QUERY}") else null,
                messages = triage(token, kept),
                hidden = page.messages.size - kept.size,
            )
            ConnectorCall.Ok(json.encodeToString(InboxResult.serializer(), result), toolError = false)
        }
    }

    /**
     * Sorts emails by importance on the phone and fills in bills' amount and due date. When the snippet lacks them,
     * the bill's text is read (at most [MAX_BODY_READS] per call) and only the two values are kept.
     */
    private suspend fun triage(token: String, emails: List<EmailHeader>): List<EmailHeader> {
        var bodyReads = 0
        val tagged = emails.map { e ->
            val kind = EmailTriage.classify(e)
            if (kind != EmailTriage.Kind.BILL) return@map e.copy(kind = kind.name)
            val head = e.subject + " " + e.snippet
            var amount = EmailTriage.extractAmount(head)
            var due = EmailTriage.extractDueDate(head)
            if ((amount == null || due == null) && e.id.isNotBlank() && bodyReads < MAX_BODY_READS) {
                bodyReads++
                val body = runCatching { api.bodyText(token, e.id) }.getOrNull()
                if (body != null) {
                    amount = amount ?: EmailTriage.extractAmount(body)
                    due = due ?: EmailTriage.extractDueDate(body)
                }
            }
            e.copy(kind = kind.name, amount = amount, due = due)
        }
        return EmailTriage.sort(tagged)
    }

    /** Gets a token, retries once with a fresh one if Gmail rejects it, and maps failures. */
    private suspend fun withToken(block: suspend (String) -> ConnectorCall): ConnectorCall {
        repeat(2) { attempt ->
            val token = try {
                tokens.accessToken()
            } catch (e: Exception) {
                return ConnectorCall.Failed(e.message ?: "Google Play services error")
            }
            if (token == null) {
                needsReauth.value = true
                audit.record(AuditEvent(AuditCategory.CONNECTOR, "token", "reauth_needed", detail = id))
                return ConnectorCall.NeedsSignIn
            }
            try {
                return block(token).also { needsReauth.value = false }
            } catch (_: GmailAuthException) {
                tokens.clearToken(token)
                if (attempt == 1) {
                    needsReauth.value = true
                    return ConnectorCall.NeedsSignIn
                }
            } catch (e: Exception) {
                return ConnectorCall.Failed(e.message ?: "Gmail couldn't be reached")
            }
        }
        return ConnectorCall.NeedsSignIn
    }

    /** No tools are offered to Claude: Gmail is answered on the phone only (D29). */
    override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> = emptyList()

    /** Free check (no Claude call): reads the latest inbox headers. */
    override suspend fun test(): ConnectorTestResult = when (val r = callTool(RECENT, JsonObject(emptyMap()))) {
        is ConnectorCall.Ok -> ConnectorTestResult.Ok
        ConnectorCall.NeedsSignIn -> ConnectorTestResult.Failed("Google access is missing or expired. Sign in again.", needsReauth = true)
        is ConnectorCall.Failed -> ConnectorTestResult.Failed(r.message, needsReauth = false)
    }

    /** Forgets the link on this phone. Full revoke: myaccount.google.com/connections (docs/setup-keys.md 3.5). */
    override suspend fun signOut() {
        secrets.remove(SecretKey.GOOGLE_LINKED)
        needsReauth.value = false
        audit.record(AuditEvent(AuditCategory.CONNECTOR, "sign_out", "ok", detail = id))
    }

    companion object {
        const val UNREAD = "gmail_unread"
        const val RECENT = "gmail_recent"
        const val SEARCH = "gmail_search"
        /** Messages fetched per inbox call (headers only), enough to find the important ones among the noise. */
        const val FETCH = 25
        const val FETCH_SEARCH = 10
        /** Bills whose text may be read per call to find the amount and due date. */
        const val MAX_BODY_READS = 4

        /** Keeps a search to plain words (the sender or topic Claude passed); no quotes or operators beyond from:. */
        internal fun cleanQuery(raw: String): String? =
            raw.replace(Regex("[\"(){}\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(80).takeIf { it.isNotEmpty() }
    }
}
