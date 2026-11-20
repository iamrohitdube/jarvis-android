package dev.jarvis.connectors

import dev.jarvis.FakeSecretStore
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.connectors.oauth.OAuthClient
import dev.jarvis.security.SecretKey
import dev.jarvis.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IndMoneyConnectorTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private var now = 10_000_000L
    private lateinit var secrets: FakeSecretStore
    private lateinit var connector: IndMoneyConnector

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        secrets = FakeSecretStore()
        connector = IndMoneyConnector(secrets, OAuthClient(http, testJson, clock = { now }), McpProbe(http), clock = { now }, mcpUrl = base() + "/mcp")
    }
    @After fun tearDown() { server.close() }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("content-type", "application/json").body(body).build()

    private fun enqueueDiscovery() {
        server.enqueue(ok("""{"resource":"${base()}/mcp","authorization_servers":["${base()}/"]}"""))
        server.enqueue(ok("""{"issuer":"${base()}/","authorization_endpoint":"${base()}/authorize","token_endpoint":"${base()}/token","registration_endpoint":"${base()}/register","code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["client_secret_post"]}"""))
    }

    @Test fun fullSignInFlowStoresTokens() = runBlocking {
        enqueueDiscovery()
        server.enqueue(ok("""{"client_id":"cid","client_secret":"cs"}"""))
        val url = connector.startSignIn().toHttpUrl()
        val state = url.queryParameter("state")!!
        assertEquals(IndMoneyConnector.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("portfolio:read market:read", url.queryParameter("scope")) // read-only scopes

        server.enqueue(ok("""{"access_token":"at","expires_in":3600,"refresh_token":"rt"}"""))
        assertTrue(connector.completeSignIn("code1", state, null).isSuccess)
        assertEquals("at", secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN))
        assertEquals("rt", secrets.get(SecretKey.INDMONEY_REFRESH_TOKEN))
        assertNull(secrets.get(SecretKey.INDMONEY_PENDING_VERIFIER)) // one-time values cleared
        assertEquals(ConnectorStatus.Connected, connector.status.first())
        assertEquals("at", connector.mcpServer()!!.authorizationToken)
    }

    @Test fun mismatchedStateIsRejected() = runBlocking {
        enqueueDiscovery()
        server.enqueue(ok("""{"client_id":"cid"}"""))
        connector.startSignIn()
        val result = connector.completeSignIn("code1", "attacker-state", null)
        assertTrue(result.isFailure)
        assertNull(secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN))
        // The pending verifier is consumed, so a replay with the right state can't succeed either.
        assertNull(secrets.get(SecretKey.INDMONEY_PENDING_STATE))
    }

    @Test fun userDenialIsReported() = runBlocking {
        val result = connector.completeSignIn(null, null, "access_denied")
        assertTrue(result.exceptionOrNull()!!.message!!.contains("access_denied"))
    }

    @Test fun expiredTokenIsRefreshedBeforeUse() = runBlocking {
        secrets.put(SecretKey.INDMONEY_ACCESS_TOKEN, "old")
        secrets.put(SecretKey.INDMONEY_REFRESH_TOKEN, "rt")
        secrets.put(SecretKey.INDMONEY_TOKEN_EXPIRES_AT, (now - 1).toString())
        secrets.put(SecretKey.INDMONEY_CLIENT_ID, "cid")
        enqueueDiscovery()
        server.enqueue(ok("""{"access_token":"new","expires_in":3600,"refresh_token":"rt2"}"""))
        assertEquals("new", connector.mcpServer()!!.authorizationToken)
        assertEquals("rt2", secrets.get(SecretKey.INDMONEY_REFRESH_TOKEN)) // rotated token stored
    }

    @Test fun failedRefreshFlagsReauth() = runBlocking {
        secrets.put(SecretKey.INDMONEY_ACCESS_TOKEN, "old")
        secrets.put(SecretKey.INDMONEY_REFRESH_TOKEN, "rt")
        secrets.put(SecretKey.INDMONEY_TOKEN_EXPIRES_AT, (now - 1).toString())
        secrets.put(SecretKey.INDMONEY_CLIENT_ID, "cid")
        enqueueDiscovery()
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
        assertNull(connector.mcpServer())
        assertEquals(ConnectorStatus.NeedsReauth, connector.status.first())
    }

    @Test fun manualTokenWithoutExpiryIsUsedAsIs() = runBlocking {
        connector.setManualToken("  pasted  ")
        assertEquals("pasted", connector.mcpServer()!!.authorizationToken)
        assertEquals(0, server.requestCount)
    }

    @Test fun testCallsMcpInitializeWithBearer() = runBlocking {
        connector.setManualToken("tok")
        server.enqueue(ok("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        assertEquals(ConnectorTestResult.Ok, connector.test())
        val r = server.takeRequest()
        assertEquals("/mcp", r.url.encodedPath)
        assertEquals("Bearer tok", r.headers["Authorization"])
    }

    @Test fun unauthorizedTestWithoutRefreshTokenNeedsReauth() = runBlocking {
        connector.setManualToken("tok")
        server.enqueue(MockResponse.Builder().code(401).build())
        val r = connector.test()
        assertTrue(r is ConnectorTestResult.Failed && r.needsReauth)
        assertEquals(ConnectorStatus.NeedsReauth, connector.status.first())
    }

    @Test fun signOutRemovesTokensButKeepsClientRegistration() = runBlocking {
        secrets.put(SecretKey.INDMONEY_CLIENT_ID, "cid")
        connector.setManualToken("tok")
        connector.signOut()
        assertNull(secrets.get(SecretKey.INDMONEY_ACCESS_TOKEN))
        assertEquals("cid", secrets.get(SecretKey.INDMONEY_CLIENT_ID))
        assertEquals(ConnectorStatus.NotConnected, connector.status.first())
    }
}
