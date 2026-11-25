package dev.jarvis.connectors

import dev.jarvis.testJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private lateinit var server: MockWebServer
    private val client = McpClient(OkHttpClient(), testJson)
    private lateinit var endpoint: McpEndpoint

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        endpoint = McpEndpoint("INDmoney", server.url("/mcp").toString(), "tok")
    }
    @After fun tearDown() { server.close() }

    private fun json(body: String, session: String? = null) = MockResponse.Builder().code(200)
        .addHeader("content-type", "application/json").apply { session?.let { addHeader("Mcp-Session-Id", it) } }
        .body(body).build()
    private fun accepted() = MockResponse.Builder().code(202).build()
    private fun initOk(session: String? = "s1") = json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""", session)
    private val noArgs = JsonObject(emptyMap())

    @Test fun initializesOnceThenCallsWithSessionAndNegotiatedVersion() = runBlocking {
        server.enqueue(initOk())
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"a"},{"type":"image"},{"type":"text","text":"b"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[],"structuredContent":{"networth":5},"isError":false}}"""))

        val first = client.callTool(endpoint, "networth_snapshot", noArgs) as McpResult.Ok
        assertEquals("a\nb", first.value.bestText)
        val second = client.callTool(endpoint, "mf_sips", noArgs) as McpResult.Ok
        assertEquals("""{"networth":5}""", second.value.bestText) // structured content preferred

        val init = server.takeRequest()
        assertEquals("Bearer tok", init.headers["Authorization"])
        assertNull(init.headers["Mcp-Session-Id"])
        val notify = server.takeRequest()
        assertEquals("s1", notify.headers["Mcp-Session-Id"])
        val call = server.takeRequest()
        assertEquals("s1", call.headers["Mcp-Session-Id"])
        assertEquals("2025-06-18", call.headers["MCP-Protocol-Version"]) // what the server chose
        assertTrue(call.headers["Accept"]!!.contains("text/event-stream"))
        assertEquals(4, server.requestCount) // one initialize for both calls
    }

    @Test fun readsTheMatchingResponseFromAnSseReply() = runBlocking {
        server.enqueue(initOk(session = null))
        server.enqueue(accepted())
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}\n\n"
        server.enqueue(MockResponse.Builder().code(200).addHeader("content-type", "text/event-stream").body(sse).build())
        assertEquals(McpResult.Ok(McpToolResult("ok", null, false)), client.callTool(endpoint, "x", noArgs))
    }

    @Test fun expiredSessionReinitializesOnce() = runBlocking {
        server.enqueue(initOk("s1")); server.enqueue(accepted())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"protocolVersion":"2025-11-25"}}""", "s2")); server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"fresh"}]}}"""))
        val r = client.callTool(endpoint, "x", noArgs) as McpResult.Ok
        assertEquals("fresh", r.value.text)
        repeat(5) { server.takeRequest() }
        assertEquals("s2", server.takeRequest().headers["Mcp-Session-Id"])
    }

    @Test fun unauthorizedAndRpcErrorsAreTyped() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(McpResult.Unauthorized, client.callTool(endpoint, "x", noArgs))

        server.enqueue(initOk()); server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"Unknown tool"}}"""))
        assertEquals(McpResult.Error("INDmoney: Unknown tool"), client.callTool(endpoint, "x", noArgs))
    }

    @Test fun toolErrorFlagIsKept() = runBlocking {
        server.enqueue(initOk()); server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"bad args"}],"isError":true}}"""))
        val r = client.callTool(endpoint, "x", noArgs) as McpResult.Ok
        assertTrue(r.value.isError)
    }

    @Test fun listToolsKeepsSchemas() = runBlocking {
        server.enqueue(initOk()); server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a","description":"A","inputSchema":{"type":"object","properties":{"k":{"type":"string"}}}},{"name":"b"}]}}"""))
        val tools = (client.listTools(endpoint) as McpResult.Ok).value
        assertEquals(listOf("a", "b"), tools.map { it.name })
        assertEquals(JsonPrimitive("object"), tools[1].inputSchema["type"]) // default schema when missing
    }
}
