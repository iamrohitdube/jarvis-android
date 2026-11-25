package dev.jarvis.connectors

import dev.jarvis.FakeSecretStore
import dev.jarvis.connectors.google.EmailFilter
import dev.jarvis.connectors.google.GmailApi
import dev.jarvis.connectors.google.GmailConnector
import dev.jarvis.connectors.google.GoogleTokenSource
import dev.jarvis.connectors.google.InboxResult
import dev.jarvis.security.SecretKey
import dev.jarvis.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class GmailConnectorTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private class FakeTokens(var tokens: ArrayDeque<String?>) : GoogleTokenSource {
        val cleared = mutableListOf<String>()
        override suspend fun accessToken(): String? = tokens.removeFirstOrNull()
        override suspend fun clearToken(token: String) { cleared += token }
    }

    /** id -> (from, subject, snippet, unread) */
    private val inbox = linkedMapOf(
        "m1" to listOf("Acme Travel <trips@acme.example>", "Your trip to Goa", "Booking confirmed", "true"),
        "m2" to listOf("HDFC Bank <alerts@hdfcbank.net>", "Statement ready", "Your statement", "true"),
        "m3" to listOf("Shop <noreply@shop.example>", "Your OTP is 482913", "Do not share", "true"),
        "m4" to listOf("\"Priya Sharma\" <priya@example.com>", "Lunch on Friday?", "Are you free", "false"),
    )
    /** Tokens accepted by the fake Gmail; anything else gets 401. */
    private val validTokens = mutableSetOf("tok-1")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.headers["Authorization"]?.removePrefix("Bearer ") !in validTokens) return MockResponse.Builder().code(401).build()
                val path = request.url.encodedPath
                if (path == "/gmail/v1/users/me/messages") {
                    val ids = inbox.keys.joinToString(",") { """{"id":"$it","threadId":"t$it"}""" }
                    return ok("""{"messages":[$ids],"resultSizeEstimate":${inbox.size}}""")
                }
                val id = path.substringAfterLast('/')
                val m = inbox[id] ?: return MockResponse.Builder().code(404).build()
                val labels = if (m[3] == "true") "\"INBOX\",\"UNREAD\"" else "\"INBOX\""
                val at = 1_764_000_000_000L + id.drop(1).toLong() * 60_000
                return ok(
                    """{"id":"$id","labelIds":[$labels],"snippet":${q(m[2])},"internalDate":"$at","payload":{"headers":[""" +
                        """{"name":"From","value":${q(m[0])}},{"name":"Subject","value":${q(m[1])}}]}}"""
                )
            }
        }
        server.start()
    }

    @After fun tearDown() { server.close() }

    private fun q(s: String) = testJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))
    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("content-type", "application/json").body(body).build()

    private fun connector(tokens: GoogleTokenSource, linked: Boolean = true) = GmailConnector(
        FakeSecretStore(if (linked) mapOf(SecretKey.GOOGLE_LINKED to "1") else emptyMap()),
        tokens, GmailApi(OkHttpClient(), testJson, server.url("/").toString().trimEnd('/')), testJson,
    )

    private fun result(call: ConnectorCall): InboxResult =
        testJson.decodeFromString(InboxResult.serializer(), (call as ConnectorCall.Ok).text)

    @Test fun notLinkedNeedsSignInWithoutCallingGoogle() = runBlocking {
        val c = connector(FakeTokens(ArrayDeque(listOf("tok-1"))), linked = false)
        assertEquals(ConnectorCall.NeedsSignIn, c.callTool(GmailConnector.UNREAD, JsonObject(emptyMap())))
        assertEquals(ConnectorStatus.NotConnected, c.status.first())
        assertEquals(0, requests.size)
    }

    @Test fun recentIsHeadersOnlyAndPrivacyFiltered() = runBlocking {
        val r = result(connector(FakeTokens(ArrayDeque(listOf("tok-1")))).callTool(GmailConnector.RECENT, JsonObject(emptyMap())))
        val subjects = r.messages.map { it.subject }
        assertTrue("Your trip to Goa" in subjects)
        assertTrue("Lunch on Friday?" in subjects)
        assertFalse("bank sender filtered", subjects.any { "Statement" in it })
        assertFalse("OTP filtered", subjects.any { "OTP" in it })
        assertEquals(2, r.hidden)
        // Newest first.
        assertEquals("Lunch on Friday?", r.messages.first().subject)

        val list = requests.first { it.url.encodedPath == "/gmail/v1/users/me/messages" }
        assertTrue(list.url.queryParameter("q")!!.contains(EmailFilter.PROMOTIONS_QUERY))
        // Every message read is metadata only (never a body or attachment).
        requests.filter { it.url.encodedPath.count { ch -> ch == '/' } > 5 }.forEach {
            assertEquals("metadata", it.url.queryParameter("format"))
            assertEquals(listOf("From", "Subject"), it.url.queryParameterValues("metadataHeaders"))
        }
    }

    @Test fun unreadUsesUnreadQuery() = runBlocking {
        val r = result(connector(FakeTokens(ArrayDeque(listOf("tok-1")))).callTool(GmailConnector.UNREAD, JsonObject(emptyMap())))
        assertEquals(4, r.estimate)
        assertTrue(requests.first().url.queryParameter("q")!!.startsWith("in:inbox is:unread"))
    }

    @Test fun searchNeedsAQueryAndStripsOperators() = runBlocking {
        val c = connector(FakeTokens(ArrayDeque(listOf("tok-1", "tok-1"))))
        assertTrue(c.callTool(GmailConnector.SEARCH, JsonObject(emptyMap())) is ConnectorCall.Failed)
        val r = result(c.callTool(GmailConnector.SEARCH, JsonObject(mapOf("query" to JsonPrimitive("\"Acme\" (travel)")))))
        assertEquals("Acme travel", r.query)
        assertEquals("Acme travel ${EmailFilter.PROMOTIONS_QUERY}", requests.first().url.queryParameter("q"))
    }

    @Test fun rejectedTokenIsClearedAndRetriedOnce() = runBlocking {
        val tokens = FakeTokens(ArrayDeque(listOf("stale", "tok-1")))
        val r = connector(tokens).callTool(GmailConnector.RECENT, JsonObject(emptyMap()))
        assertTrue(r is ConnectorCall.Ok)
        assertEquals(listOf("stale"), tokens.cleared)
    }

    @Test fun noTokenFromGoogleMeansReauth() = runBlocking {
        val c = connector(FakeTokens(ArrayDeque(listOf(null))))
        assertEquals(ConnectorCall.NeedsSignIn, c.callTool(GmailConnector.RECENT, JsonObject(emptyMap())))
        assertEquals(ConnectorStatus.NeedsReauth, c.status.first())
        assertEquals(0, requests.size)
    }

    @Test fun twoRejectedTokensMeanReauth() = runBlocking {
        val tokens = FakeTokens(ArrayDeque(listOf("bad-1", "bad-2")))
        val c = connector(tokens)
        assertEquals(ConnectorCall.NeedsSignIn, c.callTool(GmailConnector.RECENT, JsonObject(emptyMap())))
        assertEquals(listOf("bad-1", "bad-2"), tokens.cleared)
        assertEquals(ConnectorStatus.NeedsReauth, c.status.first())
    }

    @Test fun gmailOffersNoToolsToClaude() = runBlocking {
        assertTrue(connector(FakeTokens(ArrayDeque())).toolDefinitions(listOf(GmailConnector.RECENT)).isEmpty())
    }
}
