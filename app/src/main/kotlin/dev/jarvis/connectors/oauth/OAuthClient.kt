package dev.jarvis.connectors.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class ProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String>,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
)

@Serializable
data class AuthServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethods: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_methods_supported") val tokenAuthMethods: List<String> = emptyList(),
)

@Serializable
data class ClientRegistration(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/** Tokens with an absolute expiry. toString() redacts. */
data class TokenSet(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long?) {
    fun isExpired(nowMs: Long, skewMs: Long = 60_000): Boolean = expiresAtMs != null && nowMs + skewMs >= expiresAtMs
    override fun toString() = "TokenSet(access=<redacted>, refresh=${if (refreshToken != null) "<redacted>" else "none"}, expiresAtMs=$expiresAtMs)"
}

class OAuthException(message: String, val httpCode: Int? = null, val oauthError: String? = null) : Exception(message) {
    /** invalid_grant on refresh = the user must sign in again. */
    val requiresReauth: Boolean get() = oauthError == "invalid_grant" || httpCode == 401
}

/**
 * Minimal OAuth 2.1 client for MCP servers: RFC 9728 resource metadata, RFC 8414 server metadata,
 * RFC 7591 dynamic client registration, authorization code + PKCE, refresh. Error messages never
 * include tokens or secrets.
 */
class OAuthClient(private val http: OkHttpClient, private val json: Json, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun discover(mcpUrl: String): Pair<ProtectedResourceMetadata, AuthServerMetadata> = io {
        val u = mcpUrl.toHttpUrl()
        val path = u.encodedPath.trimEnd('/')
        val prmUrl = u.newBuilder().encodedPath("/.well-known/oauth-protected-resource$path").build()
        val prm = json.decodeFromString(ProtectedResourceMetadata.serializer(), getText(prmUrl.toString()))
        val issuer = prm.authorizationServers.firstOrNull()?.toHttpUrl()
            ?: throw OAuthException("No authorization server advertised")
        val asPath = issuer.encodedPath.trimEnd('/')
        val asUrl = issuer.newBuilder().encodedPath("/.well-known/oauth-authorization-server$asPath").build()
        val asm = json.decodeFromString(AuthServerMetadata.serializer(), getText(asUrl.toString()))
        if ("S256" !in asm.codeChallengeMethods) throw OAuthException("Server does not support PKCE S256")
        prm to asm
    }

    suspend fun register(asm: AuthServerMetadata, clientName: String, redirectUri: String, scopes: List<String>): ClientRegistration = io {
        val endpoint = asm.registrationEndpoint ?: throw OAuthException("Server does not support dynamic client registration")
        val authMethod = if ("client_secret_post" in asm.tokenAuthMethods) "client_secret_post"
            else asm.tokenAuthMethods.firstOrNull() ?: "client_secret_post"
        val body = buildJsonObject {
            put("client_name", clientName)
            putJsonArray("redirect_uris") { add(redirectUri) }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", authMethod)
            if (scopes.isNotEmpty()) put("scope", scopes.joinToString(" "))
        }
        val req = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw oauthError("Client registration failed", resp.code, text)
            json.decodeFromString(ClientRegistration.serializer(), text)
        }
    }

    fun authorizeUrl(
        asm: AuthServerMetadata, client: ClientRegistration, redirectUri: String,
        scopes: List<String>, state: String, codeChallenge: String, resource: String,
    ): String = asm.authorizationEndpoint.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", client.clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", scopes.joinToString(" "))
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("resource", resource)
        .build().toString()

    suspend fun exchangeCode(
        asm: AuthServerMetadata, client: ClientRegistration, code: String,
        verifier: String, redirectUri: String, resource: String,
    ): TokenSet = tokenRequest(asm, client) {
        add("grant_type", "authorization_code")
        add("code", code)
        add("redirect_uri", redirectUri)
        add("code_verifier", verifier)
        add("resource", resource)
    }

    suspend fun refresh(asm: AuthServerMetadata, client: ClientRegistration, refreshToken: String, resource: String): TokenSet =
        tokenRequest(asm, client, previousRefresh = refreshToken) {
            add("grant_type", "refresh_token")
            add("refresh_token", refreshToken)
            add("resource", resource)
        }

    private suspend fun tokenRequest(
        asm: AuthServerMetadata, client: ClientRegistration, previousRefresh: String? = null,
        fill: FormBody.Builder.() -> Unit,
    ): TokenSet = io {
        val form = FormBody.Builder().apply(fill).apply {
            add("client_id", client.clientId)
            client.clientSecret?.let { add("client_secret", it) }
        }.build()
        val req = Request.Builder().url(asm.tokenEndpoint).header("Accept", "application/json").post(form).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw oauthError("Token request failed", resp.code, text)
            val t = json.decodeFromString(TokenResponse.serializer(), text)
            TokenSet(
                accessToken = t.accessToken,
                // Servers that rotate refresh tokens send a new one; otherwise keep the old one.
                refreshToken = t.refreshToken ?: previousRefresh,
                expiresAtMs = t.expiresIn?.let { clock() + it * 1000 },
            )
        }
    }

    private fun getText(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw OAuthException("Discovery failed (HTTP ${resp.code})", resp.code)
            resp.body.string()
        }
    }

    /** Only the OAuth `error` code is surfaced; descriptions may echo request data, so they're dropped. */
    private fun oauthError(prefix: String, code: Int, body: String): OAuthException {
        val err = runCatching {
            json.parseToJsonElement(body).let { (it as? kotlinx.serialization.json.JsonObject)?.get("error") }
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        return OAuthException("$prefix (HTTP $code${err?.let { ", $it" } ?: ""})", code, err)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: IOException) { throw OAuthException("Network error: ${e.message}") }
    }
}
