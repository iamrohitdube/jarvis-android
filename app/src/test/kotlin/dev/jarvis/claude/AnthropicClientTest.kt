package dev.jarvis.claude

import dev.jarvis.testJson
import dev.jarvis.textStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun client(key: String? = "sk-test") = AnthropicClient(
        http, testJson, apiKeyProvider = { key }, baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private val request = LlmRequest(buildJsonObject { put("model", "claude-sonnet-5-5") }, listOf("mcp-client-2025-11-20"))

    private fun sseResponse(body: String) = MockResponse.Builder()
        .code(200).addHeader("content-type", "text/event-stream").body(body).build()

    @Test fun streamsTextAndSendsRequiredHeaders() = runBlocking {
        server.enqueue(sseResponse(textStream("Hi there")))
        val events = client().stream(request).toList()

        val deltas = events.filterIsInstance<LlmEvent.Update>().map { it.update }
        assertEquals(listOf(StreamUpdate.TextDelta("Hi there")), deltas)
        val done = events.last() as LlmEvent.Completed
        assertEquals("Hi there", done.message.text)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals("sk-test", recorded.headers["x-api-key"])
        assertEquals(AnthropicClient.API_VERSION, recorded.headers["anthropic-version"])
        assertEquals("mcp-client-2025-11-20", recorded.headers["anthropic-beta"])
    }

    @Test fun missingKeyFailsWithoutNetworkCall() = runBlocking {
        try {
            client(key = null).stream(request).toList()
            fail("expected MissingKey")
        } catch (e: LlmException.MissingKey) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unauthorizedIsMappedAndNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").build())
        try {
            client().stream(request).toList(); fail()
        } catch (e: LlmException.Unauthorized) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retriesOverloadedThenSucceeds() = runBlocking {
        server.enqueue(MockResponse.Builder().code(529).addHeader("retry-after", "1").body("{}").build())
        server.enqueue(sseResponse(textStream("ok")))
        val done = client().stream(request).toList().last() as LlmEvent.Completed
        assertEquals("ok", done.message.text)
        assertEquals(2, server.requestCount)
    }

    @Test fun badRequestCarriesApiMessage() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: too large"}}""").build())
        try {
            client().stream(request).toList(); fail()
        } catch (e: LlmException.InvalidRequest) {
            assertTrue(e.apiMessage.contains("max_tokens"))
        }
    }

    @Test fun testKeyUsesModelsEndpoint() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"data":[]}""").build())
        assertTrue(client().testKey("sk-new").isSuccess)
        val r = server.takeRequest()
        assertEquals("/v1/models", r.url.encodedPath)
        assertEquals("sk-new", r.headers["x-api-key"])
    }
}
