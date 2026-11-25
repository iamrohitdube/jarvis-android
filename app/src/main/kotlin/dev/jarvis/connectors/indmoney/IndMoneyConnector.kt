package dev.jarvis.connectors.indmoney

import dev.jarvis.agent.ToolRegistry
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.McpClient
import dev.jarvis.connectors.McpEndpoint
import dev.jarvis.connectors.McpResult
import dev.jarvis.connectors.McpToolDef
import dev.jarvis.connectors.oauth.AuthServerMetadata
import dev.jarvis.connectors.oauth.ClientRegistration
import dev.jarvis.connectors.oauth.OAuthClient
import dev.jarvis.connectors.oauth.OAuthException
import dev.jarvis.connectors.oauth.Pkce
import dev.jarvis.connectors.oauth.TokenSet
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * INDmoney MCP (https://mcp.indmoney.com/mcp). OAuth 2.1 + PKCE with dynamic client registration,
 * as advertised by the server's metadata. Read-only scopes only (no insights:write).
 * Sign-in happens in the browser (Custom Tab): INDmoney credentials never touch this app.
 * The app is the MCP client (D29): the token is sent only to INDmoney, never to Anthropic.
 */
class IndMoneyConnector(
    private val secrets: SecretStore,
    private val oauth: OAuthClient,
    private val mcp: McpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mcpUrl: String = MCP_URL,
    /** Connector events (sign-in, refresh, sign-out) for the audit log. Never receives tokens. */
    private val audit: AuditSink = AuditSink.NONE,
) : Connector {
    override val id = ToolRegistry.INDMONEY
    override val displayName = "INDmoney"

    private val mutex = Mutex()
    private var metadata: AuthServerMetadata? = null
    private var toolDefs: List<McpToolDef>? = null
    private val needsReauth = MutableStateFlow(false)

    override val status: Flow<ConnectorStatus> = combine(secrets.presence(), needsReauth) { present, reauth ->
        when {
            SecretKey.INDMONEY_ACCESS_TOKEN !in present -> ConnectorStatus.NotConnected
            reauth -> ConnectorStatus.NeedsReauth
            else -> ConnectorStatus.Connected
        }
    }

    /** Step 1 of sign-in: returns the authorization URL to open in a Custom Tab. */
    suspend fun startSignIn(): String = mutex.withLock {
        val asm = metadata()
        val client = clientRegistration(asm)
        val state = Pkce.newState()
        val verifier = Pkce.newVerifier()
        // Persisted so sign-in survives the app being killed while the browser is open.
        secrets.put(SecretKey.INDMONEY_PENDING_STATE, state)
        secrets.put(SecretKey.INDMONEY_PENDING_VERIFIER, verifier)
        oauth.authorizeUrl(asm, client, REDIRECT_URI, SCOPES, state, Pkce.challenge(verifier), mcpUrl)
    }

    /** Step 2: called from the redirect activity with the query parameters. */
    suspend fun completeSignIn(code: String?, state: String?, error: String?): Result<Unit> = mutex.withLock {
        runCatching {
            val expectedState = secrets.get(SecretKey.INDMONEY_PENDING_STATE)
            val verifier = secrets.get(SecretKey.INDMONEY_PENDING_VERIFIER)
            secrets.remove(SecretKey.INDMONEY_PENDING_STATE, SecretKey.INDMONEY_PENDING_VERIFIER)
            if (error != null) throw OAuthException("Sign-in was cancelled or denied ($error)")
            if (code.isNullOrBlank()) throw OAuthException("No authorization code returned")
            if (expectedState == null || verifier == null || state != expectedState) {
                throw OAuthException("Sign-in response did not match this request. Try again.")
            }
            val asm = metadata()
            val tokens = oauth.exchangeCode(asm, clientRegistration(asm), code, verifier, REDIRECT_URI, mcpUrl)
            saveTokens(tokens)
        }.also { r ->
            val detail = when {
                r.isSuccess -> null
                error != null -> error.take(40)
                else -> "failed"
            }
            event("sign_in", if (r.isSuccess) "ok" else "failed", detail)
        }
    }

    /** Advanced fallback: paste an access token obtained elsewhere (no refresh possible). */
    suspend fun setManualToken(token: String) = mutex.withLock {
        saveTokens(TokenSet(token.trim(), refreshToken = null, expiresAtMs = null))
        event("manual_token", "ok")
    }

    override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
        val endpoint = endpoint() ?: return ConnectorCall.NeedsSignIn
        return when (val r = mcp.callTool(endpoint, name, arguments)) {
            is McpResult.Ok -> ConnectorCall.Ok(r.value.bestText, r.value.isError)
            is McpResult.Error -> ConnectorCall.Failed(r.message)
            McpResult.Unauthorized -> {
                val refreshed = refreshAfterRejection() ?: return ConnectorCall.NeedsSignIn
                when (val again = mcp.callTool(refreshed, name, arguments)) {
                    is McpResult.Ok -> ConnectorCall.Ok(again.value.bestText, again.value.isError)
                    is McpResult.Error -> ConnectorCall.Failed(again.message)
                    McpResult.Unauthorized -> { needsReauth.value = true; ConnectorCall.NeedsSignIn }
                }
            }
        }
    }

    /** Server tool definitions, fetched once per process (they're public and stable). */
    override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> {
        val all = toolDefs ?: run {
            val endpoint = endpoint() ?: return emptyList()
            val r = mcp.listTools(endpoint).let { first ->
                if (first == McpResult.Unauthorized) refreshAfterRejection()?.let { mcp.listTools(it) } ?: first else first
            }
            (r as? McpResult.Ok)?.value?.also { toolDefs = it } ?: return emptyList()
        }
        return all.filter { it.name in names }
    }

    override suspend fun test(): ConnectorTestResult = testInner().also {
        event("test", if (it is ConnectorTestResult.Ok) "ok" else "failed")
    }

    private suspend fun testInner(): ConnectorTestResult {
        val endpoint = endpoint()
            ?: return ConnectorTestResult.Failed("INDmoney is not signed in.", needsReauth = true)
        return when (val r = mcp.initialize(endpoint)) {
            is McpResult.Ok -> { needsReauth.value = false; ConnectorTestResult.Ok }
            McpResult.Unauthorized -> {
                val refreshed = refreshAfterRejection()
                if (refreshed != null && mcp.initialize(refreshed) is McpResult.Ok) {
                    ConnectorTestResult.Ok
                } else {
                    needsReauth.value = true
                    ConnectorTestResult.Failed("INDmoney sign-in expired. Sign in again.", needsReauth = true)
                }
            }
            is McpResult.Error -> ConnectorTestResult.Failed(r.message, needsReauth = false)
        }
    }

    /** The endpoint with a currently valid token (refreshing if expired), or null if not signed in. */
    internal suspend fun endpoint(): McpEndpoint? = mutex.withLock {
        val access = secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN) ?: return@withLock null
        val expiresAt = secrets.get(SecretKey.INDMONEY_TOKEN_EXPIRES_AT)?.toLongOrNull()
        val current = TokenSet(access, secrets.get(SecretKey.INDMONEY_REFRESH_TOKEN), expiresAt)
        val token = if (current.isExpired(clock())) refreshLocked(current) ?: return@withLock null else current
        McpEndpoint(name = displayName, url = mcpUrl, token = token.accessToken)
    }

    /** INDmoney rejected the token: try one refresh. */
    private suspend fun refreshAfterRejection(): McpEndpoint? = mutex.withLock {
        val access = secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN) ?: return@withLock null
        val fresh = refreshLocked(TokenSet(access, secrets.get(SecretKey.INDMONEY_REFRESH_TOKEN), null)) ?: return@withLock null
        McpEndpoint(name = displayName, url = mcpUrl, token = fresh.accessToken)
    }

    override suspend fun signOut() = mutex.withLock {
        secrets.remove(
            SecretKey.INDMONEY_ACCESS_TOKEN, SecretKey.INDMONEY_REFRESH_TOKEN, SecretKey.INDMONEY_TOKEN_EXPIRES_AT,
            SecretKey.INDMONEY_PENDING_STATE, SecretKey.INDMONEY_PENDING_VERIFIER,
        )
        needsReauth.value = false
        mcp.reset()
        event("sign_out", "ok")
    }

    /** Must hold [mutex]. Returns new tokens, or null (and flags re-auth) if refresh is impossible. */
    private suspend fun refreshLocked(current: TokenSet): TokenSet? {
        val refresh = current.refreshToken
        if (refresh == null) { needsReauth.value = true; return null }
        return try {
            val asm = metadata()
            val fresh = oauth.refresh(asm, clientRegistration(asm), refresh, mcpUrl)
            saveTokens(fresh)
            event("token_refresh", "ok")
            fresh
        } catch (e: OAuthException) {
            if (e.requiresReauth) needsReauth.value = true
            event("token_refresh", "failed", if (e.requiresReauth) "reauth_needed" else null)
            null
        }
    }

    private suspend fun event(action: String, outcome: String, detail: String? = null) =
        audit.record(AuditEvent(AuditCategory.CONNECTOR, action, outcome, tool = id, method = "oauth", detail = detail))

    private suspend fun saveTokens(t: TokenSet) {
        secrets.put(SecretKey.INDMONEY_ACCESS_TOKEN, t.accessToken)
        if (t.refreshToken != null) secrets.put(SecretKey.INDMONEY_REFRESH_TOKEN, t.refreshToken)
        else secrets.remove(SecretKey.INDMONEY_REFRESH_TOKEN)
        if (t.expiresAtMs != null) secrets.put(SecretKey.INDMONEY_TOKEN_EXPIRES_AT, t.expiresAtMs.toString())
        else secrets.remove(SecretKey.INDMONEY_TOKEN_EXPIRES_AT)
        needsReauth.value = false
    }

    private suspend fun metadata(): AuthServerMetadata =
        metadata ?: oauth.discover(mcpUrl).second.also { metadata = it }

    private suspend fun clientRegistration(asm: AuthServerMetadata): ClientRegistration {
        val existingId = secrets.get(SecretKey.INDMONEY_CLIENT_ID)
        if (existingId != null) return ClientRegistration(existingId, secrets.get(SecretKey.INDMONEY_CLIENT_SECRET))
        val reg = oauth.register(asm, CLIENT_NAME, REDIRECT_URI, SCOPES)
        secrets.put(SecretKey.INDMONEY_CLIENT_ID, reg.clientId)
        reg.clientSecret?.let { secrets.put(SecretKey.INDMONEY_CLIENT_SECRET, it) }
        return reg
    }

    companion object {
        const val MCP_URL = "https://mcp.indmoney.com/mcp"
        const val REDIRECT_URI = "dev.jarvis://oauth/indmoney"
        const val CLIENT_NAME = "JARVIS (personal Android assistant)"
        val SCOPES = listOf("portfolio:read", "market:read")
    }
}
