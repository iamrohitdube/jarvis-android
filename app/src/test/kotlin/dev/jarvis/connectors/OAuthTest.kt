package dev.jarvis.connectors

import dev.jarvis.connectors.oauth.AuthServerMetadata
import dev.jarvis.connectors.oauth.ClientRegistration
import dev.jarvis.connectors.oauth.OAuthClient
import dev.jarvis.connectors.oauth.OAuthException
import dev.jarvis.connectors.oauth.Pkce
import dev.jarvis.testJson
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PkceTest {
    @Test fun rfc7636AppendixBVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun verifierIsUrlSafeAndWithinLengthLimits() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(v, Pkce.newVerifier())
        assertNotEquals(Pkce.newState(), Pkce.newState())
    }
}

class OAuthClientTest {
    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val oauth by lazy { OAuthClient(OkHttpClient(), testJson, clock = { now }) }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("content-type", "application/json").body(body).build()
    private fun base() = server.url("/").toString().trimEnd('/')

    private fun asm() = AuthServerMetadata(
        issuer = base() + "/", authorizationEndpoint = base() + "/authorize", tokenEndpoint = base() + "/token",
        registrationEndpoint = base() + "/register", codeChallengeMethods = listOf("S256"),
        tokenAuthMethods = listOf("client_secret_post", "client_secret_basic"),
    )

    @Test fun discoverFollowsResourceThenServerMetadata() = runBlocking {
        server.enqueue(ok("""{"resource":"${base()}/mcp","authorization_servers":["${base()}/"],"scopes_supported":["portfolio:read"]}"""))
        server.enqueue(ok("""{"issuer":"${base()}/","authorization_endpoint":"${base()}/authorize","token_endpoint":"${base()}/token","registration_endpoint":"${base()}/register","code_challenge_methods_supported":["S256"]}"""))
        val (prm, asm) = oauth.discover(base() + "/mcp")
        assertEquals(listOf("portfolio:read"), prm.scopesSupported)
        assertEquals(base() + "/token", asm.tokenEndpoint)
        assertEquals("/.well-known/oauth-protected-resource/mcp", server.takeRequest().url.encodedPath)
        assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().url.encodedPath)
    }

    @Test fun discoverRejectsServersWithoutS256() = runBlocking {
        server.enqueue(ok("""{"resource":"r","authorization_servers":["${base()}/"]}"""))
        server.enqueue(ok("""{"issuer":"i","authorization_endpoint":"a","token_endpoint":"t","code_challenge_methods_supported":["plain"]}"""))
        try { oauth.discover(base() + "/mcp"); fail() } catch (e: OAuthException) { assertTrue(e.message!!.contains("S256")) }
    }

    @Test fun registerSendsRedirectAndReadOnlyScopes() = runBlocking {
        server.enqueue(ok("""{"client_id":"cid","client_secret":"csecret"}"""))
        val reg = oauth.register(asm(), "JARVIS", "dev.jarvis://oauth/indmoney", listOf("portfolio:read", "market:read"))
        assertEquals("cid", reg.clientId)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("dev.jarvis://oauth/indmoney"))
        assertTrue(body.contains("portfolio:read market:read"))
        assertTrue(body.contains("client_secret_post"))
    }

    @Test fun authorizeUrlCarriesPkceStateAndResource() {
        val url = oauth.authorizeUrl(asm(), ClientRegistration("cid"), "dev.jarvis://oauth/x", listOf("a", "b"), "st", "ch", "https://r/mcp").toHttpUrl()
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("ch", url.queryParameter("code_challenge"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals("a b", url.queryParameter("scope"))
        assertEquals("https://r/mcp", url.queryParameter("resource"))
    }

    @Test fun exchangeComputesAbsoluteExpiry() = runBlocking {
        server.enqueue(ok("""{"access_token":"at","token_type":"Bearer","expires_in":3600,"refresh_token":"rt"}"""))
        val t = oauth.exchangeCode(asm(), ClientRegistration("cid", "cs"), "code1", "verifier1", "dev.jarvis://oauth/x", "https://r/mcp")
        assertEquals("at", t.accessToken)
        assertEquals(now + 3_600_000, t.expiresAtMs)
        val form = server.takeRequest().body!!.utf8()
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code_verifier=verifier1"))
        assertTrue(form.contains("client_secret=cs"))
    }

    @Test fun refreshKeepsOldRefreshTokenWhenNotRotated() = runBlocking {
        server.enqueue(ok("""{"access_token":"at2","expires_in":60}"""))
        val t = oauth.refresh(asm(), ClientRegistration("cid", "cs"), "rt-old", "https://r/mcp")
        assertEquals("rt-old", t.refreshToken)
        assertTrue(t.isExpired(now + 1)) // within the 60 s skew
    }

    @Test fun invalidGrantRequiresReauthAndErrorHidesSecrets() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant","error_description":"token rt-secret revoked"}""").build())
        try {
            oauth.refresh(asm(), ClientRegistration("cid", "cs"), "rt-secret", "https://r/mcp"); fail()
        } catch (e: OAuthException) {
            assertTrue(e.requiresReauth)
            assertFalse(e.message!!.contains("rt-secret"))
        }
    }

    @Test fun tokenSetToStringRedacts() {
        val s = dev.jarvis.connectors.oauth.TokenSet("access-secret", "refresh-secret", null).toString()
        assertFalse(s.contains("access-secret") || s.contains("refresh-secret"))
    }
}
