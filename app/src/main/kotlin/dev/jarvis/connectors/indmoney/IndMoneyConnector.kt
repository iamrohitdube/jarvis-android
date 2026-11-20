package dev.jarvis.connectors.indmoney

import dev.jarvis.agent.ToolRegistry
import dev.jarvis.claude.McpServerConfig
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.McpProbe
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

/**
 * INDmoney MCP (https://mcp.indmoney.com/mcp). OAuth 2.1 + PKCE with dynamic client registration,
 * as advertised by the server's metadata. Read-only scopes only (no insights:write).
 * Sign-in happens in the browser (Custom Tab): INDmoney credentials never touch this app.
 */
class IndMoneyConnector(
    private val secrets: SecretStore,
    private val oauth: OAuthClient,
    private val probe: McpProbe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mcpUrl: String = MCP_URL,
) : Connector {
    override val id = ToolRegistry.INDMONEY
    override val displayName = "INDmoney"

    private val mutex = Mutex()
    private var metadata: AuthServerMetadata? = null
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
        }
    }

    /** Advanced fallback: paste an access token obtained elsewhere (no refresh possible). */
    suspend fun setManualToken(token: String) = mutex.withLock {
        saveTokens(TokenSet(token.trim(), refreshToken = null, expiresAtMs = null))
    }

    override suspend fun mcpServer(): McpServerConfig? = mutex.withLock {
        val access = secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN) ?: return@withLock null
        val expiresAt = secrets.get(SecretKey.INDMONEY_TOKEN_EXPIRES_AT)?.toLongOrNull()
        val current = TokenSet(access, secrets.get(SecretKey.INDMONEY_REFRESH_TOKEN), expiresAt)
        val token = if (current.isExpired(clock())) refreshLocked(current) ?: return@withLock null else current
        McpServerConfig(name = id, url = mcpUrl, authorizationToken = token.accessToken)
    }

    override suspend fun onAuthRejected(): Boolean = mutex.withLock {
        val access = secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN) ?: return@withLock false
        refreshLocked(TokenSet(access, secrets.get(SecretKey.INDMONEY_REFRESH_TOKEN), null)) != null
    }

    override suspend fun test(): ConnectorTestResult {
        val server = mcpServer()
            ?: return ConnectorTestResult.Failed("INDmoney is not signed in.", needsReauth = true)
        return when (val r = probe.initialize(server)) {
            McpProbe.Result.Ok -> { needsReauth.value = false; ConnectorTestResult.Ok }
            McpProbe.Result.Unauthorized -> {
                if (onAuthRejected() && probe.initialize(mcpServer() ?: server) == McpProbe.Result.Ok) {
                    ConnectorTestResult.Ok
                } else {
                    needsReauth.value = true
                    ConnectorTestResult.Failed("INDmoney sign-in expired. Sign in again.", needsReauth = true)
                }
            }
            is McpProbe.Result.Error -> ConnectorTestResult.Failed(r.message, needsReauth = false)
        }
    }

    override suspend fun signOut() = mutex.withLock {
        secrets.remove(
            SecretKey.INDMONEY_ACCESS_TOKEN, SecretKey.INDMONEY_REFRESH_TOKEN, SecretKey.INDMONEY_TOKEN_EXPIRES_AT,
            SecretKey.INDMONEY_PENDING_STATE, SecretKey.INDMONEY_PENDING_VERIFIER,
        )
        needsReauth.value = false
    }

    /** Must hold [mutex]. Returns new tokens, or null (and flags re-auth) if refresh is impossible. */
    private suspend fun refreshLocked(current: TokenSet): TokenSet? {
        val refresh = current.refreshToken
        if (refresh == null) { needsReauth.value = true; return null }
        return try {
            val asm = metadata()
            val fresh = oauth.refresh(asm, clientRegistration(asm), refresh, mcpUrl)
            saveTokens(fresh)
            fresh
        } catch (e: OAuthException) {
            if (e.requiresReauth) needsReauth.value = true
            null
        }
    }

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
