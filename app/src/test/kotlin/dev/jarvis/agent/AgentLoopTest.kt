package dev.jarvis.agent

import dev.jarvis.claude.AssembledMessage
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.claude.LlmException
import dev.jarvis.claude.LlmRequest
import dev.jarvis.claude.McpServerConfig
import dev.jarvis.claude.StreamUpdate
import dev.jarvis.claude.Usage
import dev.jarvis.claude.UsageEntry
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.data.AppSettings
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.data.SnapshotStore
import kotlinx.serialization.json.Json
import dev.jarvis.tempUsageMeter
import dev.jarvis.testDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = testDb()
    private val meter = tempUsageMeter(db)
    private val settings = AppSettings()

    @After fun tearDown() { scope.cancel(); db.close() }

    /** Scripted LLM: each call pops the next response (or throws). Records requests. */
    private class FakeLlm(vararg script: () -> List<LlmEvent>) : LlmClient {
        val queue = ArrayDeque(script.toList())
        val requests = mutableListOf<LlmRequest>()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            queue.removeFirst()().forEach { emit(it) }
        }
        override suspend fun testKey(apiKey: String) = Result.success(Unit)
    }

    private class FakeConnector(var token: String?, var refreshWorks: Boolean = true) : Connector {
        override val id = ToolRegistry.INDMONEY
        override val displayName = "INDmoney"
        override val status = flowOf(ConnectorStatus.Connected)
        var refreshes = 0
        override suspend fun mcpServer() = token?.let { McpServerConfig(id, "https://example/mcp", it) }
        override suspend fun onAuthRejected(): Boolean {
            refreshes++
            if (refreshWorks) token = "fresh" else token = null
            return refreshWorks
        }
        override suspend fun test() = ConnectorTestResult.Ok
        override suspend fun signOut() { token = null }
    }

    private fun reply(
        text: String, stop: String = "end_turn", content: List<JsonObject> = emptyList(), details: JsonObject? = null,
        usage: Usage = Usage(100, 10),
    ) = listOf(
        LlmEvent.Update(StreamUpdate.TextDelta(text)),
        LlmEvent.Completed(AssembledMessage("claude-sonnet-5-5", content, stop, details, usage), 50),
    )

    @Test fun simpleTurnFinishesAndLogsUsage() = runBlocking {
        val llm = FakeLlm({ reply("Hello.") })
        val events = AgentLoop(llm, emptyMap(), meter).run(emptyList(), "hi", settings).toList()
        val done = events.last() as AgentEvent.Finished
        assertEquals("Hello.", done.text)
        val logged = meter.entries.first()
        assertEquals(1, logged.size)
        assertEquals(100, logged[0].inputTokens)
    }

    @Test fun missingConnectorAsksForSignInWithoutCallingClaude() = runBlocking {
        val llm = FakeLlm()
        val events = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector(token = null)), meter)
            .run(emptyList(), "what's my net worth", settings).toList()
        val failed = events.last() as AgentEvent.Failed
        assertTrue(failed.error is AgentError.ConnectorSignIn)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun pauseTurnIsResumedWithAssistantContent() = runBlocking {
        val toolUse = JsonObject(mapOf("type" to JsonPrimitive("mcp_tool_use"), "id" to JsonPrimitive("t1")))
        val llm = FakeLlm({ reply("Checking. ", stop = "pause_turn", content = listOf(toolUse)) }, { reply("₹12.3 lakh.") })
        val events = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector("tok")), meter)
            .run(emptyList(), "net worth", settings).toList()

        assertEquals("Checking. ₹12.3 lakh.", (events.last() as AgentEvent.Finished).text)
        assertEquals(2, llm.requests.size)
        val msgs = llm.requests[1].body["messages"]!!.jsonArray
        val last = msgs.last().jsonObject
        assertEquals("assistant", last["role"]!!.jsonPrimitive.content)
        assertEquals("t1", last["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        // Usage summed across both requests, one log entry for the turn.
        assertEquals(200, (events.last() as AgentEvent.Finished).usage.inputTokens)
        assertEquals(1, meter.entries.first().size)
    }

    @Test fun pauseTurnContinuationsAreCapped() = runBlocking {
        val script = Array(AgentLoop.MAX_CONTINUATIONS + 1) { { reply("x", stop = "pause_turn") } }
        val llm = FakeLlm(*script)
        AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector("tok")), meter)
            .run(emptyList(), "net worth", settings).toList()
        assertEquals(AgentLoop.MAX_CONTINUATIONS + 1, llm.requests.size)
    }

    @Test fun mcpAuthErrorRefreshesTokenAndRetriesOnce() = runBlocking {
        val connector = FakeConnector("stale")
        val llm = FakeLlm(
            { throw LlmException.InvalidRequest("MCP server 'indmoney' returned 401 Unauthorized") },
            { reply("Done.") },
        )
        val events = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to connector), meter)
            .run(emptyList(), "net worth", settings).toList()
        assertTrue(events.last() is AgentEvent.Finished)
        assertEquals(1, connector.refreshes)
        val token = llm.requests[1].body["mcp_servers"]!!.jsonArray[0].jsonObject["authorization_token"]!!.jsonPrimitive.content
        assertEquals("fresh", token)
    }

    @Test fun mcpAuthErrorWithFailedRefreshAsksForSignIn() = runBlocking {
        val connector = FakeConnector("stale", refreshWorks = false)
        val llm = FakeLlm({ throw LlmException.InvalidRequest("MCP server authentication failed: invalid token") })
        val events = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to connector), meter)
            .run(emptyList(), "net worth", settings).toList()
        assertTrue((events.last() as AgentEvent.Failed).error is AgentError.ConnectorSignIn)
    }

    @Test fun refusalIsReportedWithCategory() = runBlocking {
        val details = JsonObject(mapOf("type" to JsonPrimitive("refusal"), "category" to JsonPrimitive("cyber")))
        val llm = FakeLlm({ reply("", stop = "refusal", details = details) })
        val events = AgentLoop(llm, emptyMap(), meter).run(emptyList(), "hi", settings).toList()
        assertEquals(AgentError.Refused("cyber"), (events.last() as AgentEvent.Failed).error)
    }

    @Test fun badKeyIsMapped() = runBlocking {
        val llm = FakeLlm({ throw LlmException.Unauthorized() })
        val events = AgentLoop(llm, emptyMap(), meter).run(emptyList(), "hi", settings).toList()
        assertEquals(AgentError.BadKey, (events.last() as AgentEvent.Failed).error)
    }

    @Test fun trimHistoryKeepsRecentTurnsStartingWithUser() {
        val turns = (1..30).flatMap { listOf(ChatTurn(ChatTurn.Role.USER, "u$it"), ChatTurn(ChatTurn.Role.ASSISTANT, "a$it")) }
        val trimmed = AgentLoop.trimHistory(turns)
        assertEquals(AgentLoop.MAX_HISTORY_TURNS, trimmed.size)
        assertEquals(ChatTurn.Role.USER, trimmed.first().role)
        assertEquals("a30", trimmed.last().text)
        // An odd window must not start with an assistant turn.
        assertEquals(ChatTurn.Role.USER, AgentLoop.trimHistory(turns.drop(1).take(21)).first().role)
    }

    @Test fun trimHistoryClipsLongOldTurns() {
        val long = "x".repeat(5000)
        val trimmed = AgentLoop.trimHistory(listOf(ChatTurn(ChatTurn.Role.USER, "q"), ChatTurn(ChatTurn.Role.ASSISTANT, long)))
        assertTrue(trimmed[1].text.length <= AgentLoop.MAX_TURN_CHARS + 4)
        assertTrue(trimmed[1].text.startsWith("xxx"))
    }

    private fun spend(usd: Double) = runBlocking {
        meter.record(UsageEntry(System.currentTimeMillis(), "claude-haiku-5-5", "chat", 1, 1, 0, 0, usd, 1, null, 0))
    }

    @Test fun budgetReachedStopsBeforeAnyRequest() = runBlocking {
        spend(5.0)
        val llm = FakeLlm()
        val events = AgentLoop(llm, emptyMap(), meter).run(emptyList(), "hi", settings.copy(monthlyBudgetUsd = 5.0)).toList()
        assertEquals(AgentError.BudgetReached(5.0), (events.single() as AgentEvent.Failed).error)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun budgetStopCanBeTurnedOff() = runBlocking {
        spend(5.0)
        val events = AgentLoop(FakeLlm({ reply("ok", usage = Usage()) }), emptyMap(), meter)
            .run(emptyList(), "hi", settings.copy(monthlyBudgetUsd = 5.0, stopAtBudget = false)).toList()
        assertTrue(events.last() is AgentEvent.Finished)
    }

    @Test fun underBudgetRuns() = runBlocking {
        spend(4.0)
        val events = AgentLoop(FakeLlm({ reply("ok", usage = Usage()) }), emptyMap(), meter)
            .run(emptyList(), "hi", settings.copy(monthlyBudgetUsd = 5.0)).toList()
        assertTrue(events.last() is AgentEvent.Finished)
    }

    // ---- Phase 2: snapshots, data stamps, audit ----

    private val snapshots = SnapshotStore(db.snapshots())

    private class RecordingAudit : AuditSink {
        val events = mutableListOf<AuditEvent>()
        override suspend fun record(event: AuditEvent) { events += event }
    }

    private fun block(s: String) = Json.parseToJsonElement(s).jsonObject
    private val netWorthCall = listOf(
        block("""{"type":"mcp_tool_use","id":"t1","name":"networth_snapshot","server_name":"indmoney","input":{}}"""),
        block("""{"type":"mcp_tool_result","tool_use_id":"t1","is_error":false,"content":[{"type":"text","text":"TOTAL_100"}]}"""),
    )

    @Test fun liveCallSavesSnapshotStampsReplyAndAudits() = runBlocking {
        val audit = RecordingAudit()
        val llm = FakeLlm({ reply("About 1 lakh.", content = netWorthCall) })
        val events = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector("tok")), meter, snapshots = snapshots, audit = audit)
            .run(emptyList(), "what's my net worth", settings).toList()

        val done = events.last() as AgentEvent.Finished
        assertEquals(listOf("INDmoney/networth_snapshot/live"), done.dataStamps.map { "${it.source}/${it.tool}/${if (it.cached) "cached" else "live"}" })
        assertEquals("TOTAL_100", snapshots.all.first().single().content)
        assertTrue(llm.requests[0].body["mcp_servers"] != null)

        val tool = audit.events.single { it.category == AuditCategory.TOOL }
        assertEquals(listOf("call", "ok", "networth_snapshot", "SENSITIVE"), listOf(tool.action, tool.outcome, tool.tool, tool.tier))
        assertEquals("ok", audit.events.single { it.category == AuditCategory.REQUEST }.outcome)
    }

    @Test fun freshSnapshotIsReusedWithoutCallingTheConnector() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", "TOTAL_100")
        val audit = RecordingAudit()
        val llm = FakeLlm({ reply("Still about 1 lakh.") })
        // Signed-out connector: any attempt to use it would fail with ConnectorSignIn.
        val events = AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector(token = null)), meter, snapshots = snapshots, audit = audit)
            .run(emptyList(), "what's my net worth", settings).toList()

        assertTrue((events.first() as AgentEvent.Routed).fromCache)
        val body = llm.requests.single().body
        assertTrue(body["mcp_servers"] == null && body["tools"] == null)
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        val data = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(data, data.contains("<cached_data source=\"INDmoney\" tool=\"networth_snapshot\""))
        assertTrue(data.contains("TOTAL_100"))
        assertEquals("what's my net worth", content[1].jsonObject["text"]!!.jsonPrimitive.content)

        val done = events.last() as AgentEvent.Finished
        assertTrue(done.dataStamps.single().cached)
        assertTrue(audit.events.any { it.action == "cache_hit" && it.tool == "networth_snapshot" })
    }

    @Test fun askingForLatestBypassesSavedData() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", "old")
        val llm = FakeLlm({ reply("Fresh numbers.", content = netWorthCall) })
        AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector("tok")), meter, snapshots = snapshots)
            .run(emptyList(), "refresh my net worth", settings).toList()
        assertTrue(llm.requests.single().body["mcp_servers"] != null)
        assertEquals("TOTAL_100", snapshots.all.first().single().content) // replaced with the live result
    }

    @Test fun reuseCanBeTurnedOff() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", "old")
        val llm = FakeLlm({ reply("Live.") })
        AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector("tok")), meter, snapshots = snapshots)
            .run(emptyList(), "net worth", settings.copy(snapshotMaxAgeMinutes = 0)).toList()
        assertTrue(llm.requests.single().body["mcp_servers"] != null)
    }

    @Test fun argumentedOrFailedCallsAreNotSaved() = runBlocking {
        val calls = listOf(
            block("""{"type":"mcp_tool_use","id":"h","name":"networth_holdings","server_name":"indmoney","input":{"asset":"stocks"}}"""),
            block("""{"type":"mcp_tool_result","tool_use_id":"h","is_error":false,"content":"stocks only"}"""),
            block("""{"type":"mcp_tool_use","id":"n","name":"networth_snapshot","server_name":"indmoney","input":{}}"""),
            block("""{"type":"mcp_tool_result","tool_use_id":"n","is_error":true,"content":"oops"}"""),
        )
        val llm = FakeLlm({ reply("Partial.", content = calls) })
        AgentLoop(llm, mapOf(ToolRegistry.INDMONEY to FakeConnector("tok")), meter, snapshots = snapshots)
            .run(emptyList(), "net worth and holdings", settings).toList()
        assertTrue(snapshots.all.first().isEmpty())
    }

    @Test fun refusalIsAudited() = runBlocking {
        val audit = RecordingAudit()
        val details = JsonObject(mapOf("category" to JsonPrimitive("cyber")))
        AgentLoop(FakeLlm({ reply("", stop = "refusal", details = details) }), emptyMap(), meter, audit = audit)
            .run(emptyList(), "hi", settings).toList()
        assertEquals("refused", audit.events.single { it.category == AuditCategory.REQUEST }.outcome)
    }

    @Test fun mcpAuthHeuristic() {
        assertTrue(AgentLoop.looksLikeMcpAuthError("MCP server 'x' returned 401"))
        assertTrue(!AgentLoop.looksLikeMcpAuthError("max_tokens: must be positive"))
    }
}
