package dev.jarvis.agent

import dev.jarvis.FakeSecretStore
import dev.jarvis.allowAll
import dev.jarvis.claude.AnthropicClient
import dev.jarvis.connectors.McpClient
import dev.jarvis.connectors.indmoney.IndMoneyConnector
import dev.jarvis.connectors.oauth.OAuthClient
import dev.jarvis.data.AppSettings
import dev.jarvis.data.SnapshotStore
import dev.jarvis.memory.HistoryStore
import dev.jarvis.security.SecretKey
import dev.jarvis.sse
import dev.jarvis.tempUsageMeter
import dev.jarvis.testDb
import dev.jarvis.testJson
import dev.jarvis.textStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

/**
 * D29 "done when": no Anthropic request ever contains a connector token or personal data. End to end with the
 * real INDmoney connector, MCP client, history store and Anthropic client, each against a local mock server.
 */
class PrivateDataTest {
    private lateinit var indmoney: MockWebServer
    private lateinit var anthropic: MockWebServer
    private val db = testDb()
    private val http = OkHttpClient()

    private val token = "tok-SECRET-4242"
    private val holdingName = "ACME-HOLDING-7781"
    private val netWorth = "1234567"

    @Before fun setUp() {
        indmoney = MockWebServer(); indmoney.start()
        anthropic = MockWebServer(); anthropic.start()
    }
    @After fun tearDown() { indmoney.close(); anthropic.close(); db.close() }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("content-type", "application/json").body(body).build()
    private fun stream(body: String) = MockResponse.Builder().code(200).addHeader("content-type", "text/event-stream").body(body).build()

    private fun toolUseStream(id: String, name: String, input: String) = sse(
        "message_start" to """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[],"usage":{"input_tokens":50,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":${testJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(input))}}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":10}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private fun RecordedRequest.text(): String = body?.utf8().orEmpty()

    @Test fun noTokenOrPortfolioDataEverReachesAnthropic() = runBlocking {
        val secrets = FakeSecretStore(mapOf(SecretKey.INDMONEY_ACCESS_TOKEN to token))
        val connector = IndMoneyConnector(secrets, OAuthClient(http, testJson), McpClient(http, testJson), mcpUrl = indmoney.url("/mcp").toString())
        val llm = AnthropicClient(http, testJson, apiKeyProvider = { "sk-test" }, baseUrl = anthropic.url("/").toString().trimEnd('/'), maxRetries = 0)
        val history = HistoryStore(db.history())
        val agent = AgentLoop(llm, mapOf(connector.id to connector), tempUsageMeter(db), allowAll, snapshots = SnapshotStore(db.snapshots()))
        val settings = AppSettings()

        // INDmoney (phone side): initialize, then the personal reads, then the public price tool.
        // INDmoney (phone side): initialize, the public tool list (offered to Claude), then the reads.
        indmoney.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25"}}""")); indmoney.enqueue(MockResponse.Builder().code(202).build())
        indmoney.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_indian_stocks_details","description":"price","inputSchema":{"type":"object"}},{"name":"lookup_ind_keys","description":"lookup","inputSchema":{"type":"object"}}]}}"""))
        indmoney.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"networth\":$netWorth}"}]}}"""))
        indmoney.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{\"holdings\":[{\"name\":\"$holdingName\",\"current_value\":500000}]}"}]}}"""))
        indmoney.enqueue(json("""{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"{\"price\":2950}"}]}}"""))
        // Anthropic categorises every question: two hand-backs, then a public tool round and the answer.
        anthropic.enqueue(stream(toolUseStream("p1", "private_data", """{"kinds":["net_worth"]}""")))
        anthropic.enqueue(stream(toolUseStream("p2", "private_data", """{"kinds":["holdings"]}""")))
        anthropic.enqueue(stream(toolUseStream("t1", "get_indian_stocks_details", """{"key":"RELIANCE"}""")))
        anthropic.enqueue(stream(textStream("About 2950 rupees.")))

        val conversation = history.startConversation("chat")
        suspend fun ask(q: String): AgentEvent {
            val context = history.contextTurns(conversation)
            history.addUserMessage(conversation, q)
            val last = agent.run(context, q, settings).toList().last()
            if (last is AgentEvent.Finished) {
                history.addAssistantMessage(conversation, last.text, last.model, last.costUsd, last.dataStamps, sensitive = last.sensitive, screenOnly = last.screenOnly)
            }
            return last
        }

        val nw = ask("what's my net worth") as AgentEvent.Finished
        assertTrue(nw.text, nw.text.contains("12.3 lakh"))
        val holdings = ask("show my holdings") as AgentEvent.Finished
        assertTrue(holdings.text.contains(holdingName))
        val price = ask("reliance share price") as AgentEvent.Finished
        assertEquals("About 2950 rupees.", price.text)

        // Every Anthropic request: no token, no holdings, no net worth, no connector config.
        assertEquals(4, anthropic.requestCount)
        repeat(anthropic.requestCount) {
            val r = anthropic.takeRequest()
            val all = r.text() + r.headers.toString()
            listOf(token, holdingName, netWorth, "12.3 lakh", "mcp_servers", "authorization_token", "mcp-client").forEach { secret ->
                assertFalse("Anthropic request contains $secret", all.contains(secret))
            }
            assertEquals("/v1/messages", r.url.encodedPath)
        }
        // The token went only to INDmoney.
        repeat(indmoney.requestCount) { assertEquals("Bearer $token", indmoney.takeRequest().headers["Authorization"]) }
    }

    @Test fun noGoogleTokenOrEmailContentEverReachesAnthropic() = runBlocking {
        val googleToken = "ya-GOOGLE-TOKEN-9911"
        val subject = "SECRET-SUBJECT-5150"
        val sender = "Zed Q Privatesender"
        val gmailServer = MockWebServer()
        gmailServer.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath.endsWith("/messages")) json("""{"messages":[{"id":"a1"}],"resultSizeEstimate":1}""")
                else json(
                    """{"id":"a1","labelIds":["INBOX","UNREAD"],"snippet":"see you","internalDate":"1764000000000",""" +
                        """"payload":{"headers":[{"name":"From","value":"$sender <zed@example.com>"},{"name":"Subject","value":"$subject"}]}}"""
                )
        }
        gmailServer.start()
        try {
            val tokens = object : dev.jarvis.connectors.google.GoogleTokenSource {
                override suspend fun accessToken() = googleToken
                override suspend fun clearToken(token: String) {}
            }
            val gmail = dev.jarvis.connectors.google.GmailConnector(
                FakeSecretStore(mapOf(SecretKey.GOOGLE_LINKED to "1")), tokens,
                dev.jarvis.connectors.google.GmailApi(http, testJson, gmailServer.url("/").toString().trimEnd('/')), testJson,
            )
            val llm = AnthropicClient(http, testJson, apiKeyProvider = { "sk-test" }, baseUrl = anthropic.url("/").toString().trimEnd('/'), maxRetries = 0)
            val history = HistoryStore(db.history())
            val agent = AgentLoop(llm, mapOf(gmail.id to gmail), tempUsageMeter(db), allowAll, snapshots = SnapshotStore(db.snapshots()))

            // Claude hands both questions back; the second is a follow-up that carries the first in history.
            anthropic.enqueue(stream(toolUseStream("e1", "private_data", """{"kinds":["unread_email"]}""")))
            anthropic.enqueue(stream(toolUseStream("e2", "private_data", """{"kinds":["email_search"],"item":"Zed"}""")))

            val conversation = history.startConversation("chat")
            suspend fun ask(q: String): AgentEvent {
                val context = history.contextTurns(conversation)
                history.addUserMessage(conversation, q)
                val last = agent.run(context, q, AppSettings()).toList().last()
                if (last is AgentEvent.Finished) {
                    history.addAssistantMessage(conversation, last.text, last.model, last.costUsd, last.dataStamps, sensitive = last.sensitive, screenOnly = last.screenOnly)
                }
                return last
            }
            val unread = ask("any new emails?") as AgentEvent.Finished
            assertTrue(unread.text, unread.text.contains(subject) && unread.text.contains(sender))
            val search = ask("anything from Zed?") as AgentEvent.Finished
            assertTrue(search.text, search.text.contains(subject))

            assertEquals(2, anthropic.requestCount)
            repeat(anthropic.requestCount) {
                val r = anthropic.takeRequest()
                val all = r.text() + r.headers.toString()
                listOf(googleToken, subject, sender, "zed@example.com", "see you").forEach { secret ->
                    assertFalse("Anthropic request contains $secret", all.contains(secret))
                }
            }
            // The Google token went only to Gmail.
            repeat(gmailServer.requestCount) { assertEquals("Bearer $googleToken", gmailServer.takeRequest().headers["Authorization"]) }
        } finally {
            gmailServer.close()
        }
    }
}
