package dev.jarvis.agent

import dev.jarvis.claude.AssembledMessage
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.claude.LlmException
import dev.jarvis.claude.LlmRequest
import dev.jarvis.claude.StreamUpdate
import dev.jarvis.claude.Usage
import dev.jarvis.claude.UsageEntry
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.McpToolDef
import dev.jarvis.data.AppSettings
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.data.SnapshotStore
import dev.jarvis.security.AccessController
import dev.jarvis.security.AccessResult
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.DenyReason
import dev.jarvis.tempUsageMeter
import dev.jarvis.testDb
import dev.jarvis.allowAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val db = testDb()
    private val meter = tempUsageMeter(db)
    private val settings = AppSettings()

    @After fun tearDown() { db.close() }

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

    /** Phone-side connector fake: scripted results per tool, records calls. */
    private class FakeConnector(var signedIn: Boolean = true) : Connector {
        override val id = ToolRegistry.INDMONEY
        override val displayName = "INDmoney"
        override val status get() = flowOf(if (signedIn) ConnectorStatus.Connected else ConnectorStatus.NotConnected)
        val results = mutableMapOf<String, ConnectorCall>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
            calls += name to arguments
            if (!signedIn) return ConnectorCall.NeedsSignIn
            return results[name] ?: ConnectorCall.Ok("""{"result":"$name"}""", toolError = false)
        }
        override suspend fun toolDefinitions(names: Collection<String>) =
            if (signedIn) names.map { McpToolDef(it, "about $it", JsonObject(mapOf("type" to JsonPrimitive("object")))) } else emptyList()
        override suspend fun test() = ConnectorTestResult.Ok
        override suspend fun signOut() { signedIn = false }
    }

    private fun reply(
        text: String, stop: String = "end_turn", content: List<JsonObject> = emptyList(), details: JsonObject? = null,
        usage: Usage = Usage(100, 10),
    ): List<LlmEvent> = listOfNotNull(
        text.takeIf { it.isNotEmpty() }?.let { LlmEvent.Update(StreamUpdate.TextDelta(it)) },
        LlmEvent.Completed(AssembledMessage("claude-sonnet-5-5", content, stop, details, usage), 50),
    )

    private fun block(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun toolUse(id: String, name: String, input: String = "{}") =
        block("""{"type":"tool_use","id":"$id","name":"$name","input":$input}""")

    /** Claude's routing decision: hand the question back to the phone. */
    private fun handoff(vararg kinds: String = arrayOf("net_worth"), item: String? = null): () -> List<LlmEvent> = {
        val k = kinds.joinToString(",") { "\"$it\"" }
        val i = item?.let { ""","item":"$it"""" }.orEmpty()
        reply("", stop = "tool_use", content = listOf(toolUse("p1", PrivateDataTool.NAME, """{"kinds":[$k]$i}""")))
    }

    private fun indmoney(c: FakeConnector = FakeConnector()) = mapOf(ToolRegistry.INDMONEY to c)

    @Test fun simpleTurnFinishesAndLogsUsage() = runBlocking {
        val llm = FakeLlm({ reply("Hello.") })
        val events = AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings).toList()
        val done = events.last() as AgentEvent.Finished
        assertEquals("Hello.", done.text)
        val logged = meter.entries.first()
        assertEquals(1, logged.size)
        assertEquals(100, logged[0].inputTokens)
    }

    @Test fun pauseTurnIsResumedWithAssistantContent() = runBlocking {
        val paused = block("""{"type":"server_tool_use","id":"s1"}""")
        val llm = FakeLlm({ reply("Checking. ", stop = "pause_turn", content = listOf(paused)) }, { reply("Done.") })
        val events = AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings).toList()

        assertEquals("Checking. Done.", (events.last() as AgentEvent.Finished).text)
        assertEquals(2, llm.requests.size)
        val last = llm.requests[1].body["messages"]!!.jsonArray.last().jsonObject
        assertEquals("assistant", last["role"]!!.jsonPrimitive.content)
        assertEquals("s1", last["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(200, (events.last() as AgentEvent.Finished).usage.inputTokens)
        assertEquals(1, meter.entries.first().size)
    }

    @Test fun pauseTurnContinuationsAreCapped() = runBlocking {
        val script = Array(AgentLoop.MAX_CONTINUATIONS + 1) { { reply("x", stop = "pause_turn") } }
        val llm = FakeLlm(*script)
        AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings).toList()
        assertEquals(AgentLoop.MAX_CONTINUATIONS + 1, llm.requests.size)
    }

    @Test fun refusalIsReportedWithCategory() = runBlocking {
        val details = JsonObject(mapOf("type" to JsonPrimitive("refusal"), "category" to JsonPrimitive("cyber")))
        val llm = FakeLlm({ reply("", stop = "refusal", details = details) })
        val events = AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings).toList()
        assertEquals(AgentError.Refused("cyber"), (events.last() as AgentEvent.Failed).error)
    }

    @Test fun badKeyIsMapped() = runBlocking {
        val llm = FakeLlm({ throw LlmException.Unauthorized() })
        val events = AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings).toList()
        assertEquals(AgentError.BadKey, (events.last() as AgentEvent.Failed).error)
    }

    @Test fun trimHistoryKeepsRecentTurnsStartingWithUser() {
        val turns = (1..30).flatMap { listOf(ChatTurn(ChatTurn.Role.USER, "u$it"), ChatTurn(ChatTurn.Role.ASSISTANT, "a$it")) }
        val trimmed = AgentLoop.trimHistory(turns)
        assertEquals(AgentLoop.MAX_HISTORY_TURNS, trimmed.size)
        assertEquals(ChatTurn.Role.USER, trimmed.first().role)
        assertEquals("a30", trimmed.last().text)
        assertEquals(ChatTurn.Role.USER, AgentLoop.trimHistory(turns.drop(1).take(21)).first().role)
    }

    @Test fun trimHistoryClipsLongOldTurns() {
        val long = "x".repeat(5000)
        val trimmed = AgentLoop.trimHistory(listOf(ChatTurn(ChatTurn.Role.USER, "q"), ChatTurn(ChatTurn.Role.ASSISTANT, long)))
        assertTrue(trimmed[1].text.length <= AgentLoop.MAX_TURN_CHARS + 4)
        assertTrue(trimmed[1].text.startsWith("xxx"))
    }

    @Test fun privateAnswersAreReplacedButTheQuestionKeepsContext() = runBlocking {
        val history = listOf(
            ChatTurn(ChatTurn.Role.USER, "what's my net worth", personal = true),
            ChatTurn(ChatTurn.Role.ASSISTANT, "Net worth is ₹12.4 lakh.", personal = true),
        )
        assertEquals(listOf("what's my net worth", AgentLoop.PRIVATE_ANSWER), AgentLoop.trimHistory(history).map { it.text })
        val llm = FakeLlm(handoff("sips"))
        AgentLoop(llm, indmoney(), meter, allowAll).run(history, "and my SIPs?", settings).toList()
        val messages = llm.requests.single().body["messages"].toString()
        assertFalse(messages.contains("12.4 lakh"))
        assertTrue(messages.contains(AgentLoop.PRIVATE_ANSWER) && messages.contains("what's my net worth"))
    }

    // ---- Budget ----

    private fun spend(usd: Double) = runBlocking {
        meter.record(UsageEntry(System.currentTimeMillis(), "claude-haiku-5-5", "chat", 1, 1, 0, 0, usd, 1, null, 0))
    }

    @Test fun budgetReachedStopsBeforeAnyRequest() = runBlocking {
        spend(5.0)
        val llm = FakeLlm()
        val events = AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings.copy(monthlyBudgetUsd = 5.0)).toList()
        assertEquals(AgentError.BudgetReached(5.0), (events.single() as AgentEvent.Failed).error)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun budgetReachedStillAnswersClearPersonalQuestionsOnThePhone() = runBlocking {
        spend(5.0)
        val llm = FakeLlm()
        val events = AgentLoop(llm, indmoney(), meter, allowAll)
            .run(emptyList(), "what's my net worth", settings.copy(monthlyBudgetUsd = 5.0)).toList()
        assertTrue(events.last() is AgentEvent.Finished)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun budgetStopCanBeTurnedOff() = runBlocking {
        spend(5.0)
        val events = AgentLoop(FakeLlm({ reply("ok", usage = Usage()) }), emptyMap(), meter, allowAll)
            .run(emptyList(), "hi", settings.copy(monthlyBudgetUsd = 5.0, stopAtBudget = false)).toList()
        assertTrue(events.last() is AgentEvent.Finished)
    }

    @Test fun underBudgetRuns() = runBlocking {
        spend(4.0)
        val events = AgentLoop(FakeLlm({ reply("ok", usage = Usage()) }), emptyMap(), meter, allowAll)
            .run(emptyList(), "hi", settings.copy(monthlyBudgetUsd = 5.0)).toList()
        assertTrue(events.last() is AgentEvent.Finished)
    }

    // ---- D29 option B: Claude categorises, personal answers stay on the phone ----

    private val snapshots = SnapshotStore(db.snapshots())

    private class RecordingAudit : AuditSink {
        val events = mutableListOf<AuditEvent>()
        override suspend fun record(event: AuditEvent) { events += event }
    }

    @Test fun everyQuestionIsCategorisedByClaudeWithOnlyPublicAndHandBackTools() = runBlocking {
        val llm = FakeLlm(handoff())
        AgentLoop(llm, indmoney(), meter, allowAll).run(emptyList(), "how much is sitting in HDFC", settings).toList()
        val offered = llm.requests.single().body["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(PrivateDataTool.NAME in offered)
        assertTrue("get_indian_stocks_details" in offered)
        assertTrue(ToolRegistry.all.filter { it.isPersonal }.none { it.name in offered }) // no personal tool is ever offered
    }

    @Test fun handBackIsAnsweredOnThePhoneAndClaudeNeverSeesTheData() = runBlocking {
        val audit = RecordingAudit()
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Ok("""{"networth":1240000}""", false) }
        val llm = FakeLlm(handoff())
        val events = AgentLoop(llm, indmoney(connector), meter, allowAll, snapshots = snapshots, audit = audit)
            .run(emptyList(), "am I rich yet", settings).toList()

        assertEquals(1, llm.requests.size) // only the routing call
        val done = events.last() as AgentEvent.Finished
        assertEquals("Net worth is ₹12.4 lakh.", done.text)
        assertEquals(AgentLoop.LOCAL_MODEL, done.model)
        assertTrue(done.personal)
        assertEquals(listOf("INDmoney/networth_snapshot/live"), done.dataStamps.map { "${it.source}/${it.tool}/${if (it.cached) "cached" else "live"}" })
        assertEquals("""{"networth":1240000}""", snapshots.all.first().single().content)
        assertEquals(1, meter.entries.first().size) // the routing call is costed

        val tool = audit.events.single { it.category == AuditCategory.TOOL }
        assertEquals(listOf("call", "ok", "networth_snapshot", "SENSITIVE"), listOf(tool.action, tool.outcome, tool.tool, tool.tier))
        assertTrue(audit.events.any { it.category == AuditCategory.REQUEST && it.outcome == "handoff" })
    }

    @Test fun bankHandBackReadsTheBankFigure() = runBlocking {
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Ok("""{"networth":900000,"bank_balance":52000}""", false) }
        val gate = ScriptedGate { AccessResult.Granted(it, AuthMethod.BIOMETRIC, screenOnly = true, sensitive = true) }
        val events = AgentLoop(FakeLlm(handoff("bank_balance")), indmoney(connector), meter, gate)
            .run(emptyList(), "how much is sitting in HDFC", settings).toList()
        assertEquals(listOf("networth_snapshot"), gate.calls.single().first) // same security gate
        val done = events.last() as AgentEvent.Finished
        assertEquals("Bank and savings balance is ₹52,000.", done.text)
        assertTrue(done.personal && done.sensitive && done.screenOnly)
    }

    @Test fun severalKindsInOneQuestion() = runBlocking {
        val connector = FakeConnector()
        AgentLoop(FakeLlm(handoff("net_worth", "sips")), indmoney(connector), meter, allowAll)
            .run(emptyList(), "net worth and how my SIPs are doing", settings).toList()
        assertEquals(listOf("networth_snapshot", "mf_sips", "indian_stocks_sips"), connector.calls.map { it.first })
    }

    @Test fun oneSpecificHolding() = runBlocking {
        val connector = FakeConnector().apply {
            results["networth_holdings"] = ConnectorCall.Ok("""{"holdings":[{"name":"Reliance Industries","current_value":250000,"pnl_percent":8.5},{"name":"TCS","current_value":90000}]}""", false)
        }
        val done = AgentLoop(FakeLlm(handoff("holdings", item = "reliance")), indmoney(connector), meter, allowAll)
            .run(emptyList(), "how are my reliance shares doing", settings).toList().last() as AgentEvent.Finished
        assertEquals("Reliance Industries: ₹2.5 lakh (+8.5%).", done.text)
    }

    @Test fun signedOutConnectorAsksForSignIn() = runBlocking {
        val llm = FakeLlm(handoff())
        val events = AgentLoop(llm, indmoney(FakeConnector(signedIn = false)), meter, allowAll)
            .run(emptyList(), "what's my net worth", settings).toList()
        assertTrue((events.last() as AgentEvent.Failed).error is AgentError.ConnectorSignIn)
        // Signed out: only the hand-back is offered (market tools need the connector).
        assertEquals(listOf(PrivateDataTool.NAME), llm.requests.single().body["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test fun claudeUnreachableFallsBackToThePhraseListOnThePhone() = runBlocking {
        val llm = FakeLlm({ throw LlmException.Network(java.io.IOException("offline")) }, { throw LlmException.Network(java.io.IOException("offline")) })
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Ok("""{"networth":100000}""", false) }
        val done = AgentLoop(llm, indmoney(connector), meter, allowAll)
            .run(emptyList(), "what's my net worth", settings).toList().last() as AgentEvent.Finished
        assertEquals("Net worth is ₹1 lakh.", done.text)

        val general = AgentLoop(llm, indmoney(), meter, allowAll).run(emptyList(), "tell me a joke", settings).toList()
        assertTrue((general.last() as AgentEvent.Failed).error is AgentError.Network)
    }

    @Test fun freshSnapshotIsReusedWithoutCallingTheConnector() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", """{"networth":100000}""")
        val audit = RecordingAudit()
        val connector = FakeConnector()
        val events = AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, snapshots = snapshots, audit = audit)
            .run(emptyList(), "what's my net worth", settings).toList()

        assertTrue(events.filterIsInstance<AgentEvent.Routed>().last().fromCache)
        assertTrue(connector.calls.isEmpty())
        val done = events.last() as AgentEvent.Finished
        assertEquals("Net worth is ₹1 lakh.", done.text)
        assertTrue(done.dataStamps.single().cached)
        assertTrue(audit.events.any { it.action == "cache_hit" && it.tool == "networth_snapshot" })
    }

    @Test fun askingForLatestBypassesSavedData() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", "old")
        val connector = FakeConnector()
        AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, snapshots = snapshots)
            .run(emptyList(), "refresh my net worth", settings).toList()
        assertEquals(1, connector.calls.size)
        assertEquals("""{"result":"networth_snapshot"}""", snapshots.all.first().single().content)
    }

    @Test fun reuseCanBeTurnedOff() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", "old")
        val connector = FakeConnector()
        AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, snapshots = snapshots)
            .run(emptyList(), "net worth", settings.copy(snapshotMaxAgeMinutes = 0)).toList()
        assertEquals(1, connector.calls.size)
    }

    @Test fun indmoneyUnreachableUsesTheLastSavedResultWithItsAge() = runBlocking {
        var now = 1_000_000L
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", """{"networth":200000}""", at = now)
        now += 3 * 60 * 60_000L
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Failed("Couldn't reach INDmoney") }
        val done = AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, clock = { now }, snapshots = snapshots)
            .run(emptyList(), "net worth", settings).toList().last() as AgentEvent.Finished
        assertTrue(done.text, done.text.startsWith("Couldn't reach INDmoney, so this is from 3 h ago."))
        assertTrue(done.text.contains("₹2 lakh"))
        assertTrue(done.dataStamps.single().cached)
    }

    @Test fun indmoneyUnreachableWithNothingSavedFails() = runBlocking {
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Failed("Couldn't reach INDmoney") }
        val events = AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, snapshots = snapshots)
            .run(emptyList(), "net worth", settings).toList()
        assertTrue((events.last() as AgentEvent.Failed).error is AgentError.Network)
    }

    @Test fun toolErrorsAreNotSaved() = runBlocking {
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Ok("oops", toolError = true) }
        val done = AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, snapshots = snapshots)
            .run(emptyList(), "net worth", settings).toList().last() as AgentEvent.Finished
        assertEquals("INDmoney couldn't get that right now.", done.text)
        assertTrue(snapshots.all.first().isEmpty())
    }

    @Test fun shapeLogHasFieldNamesButNoValues() = runBlocking {
        val logs = mutableListOf<String>()
        val connector = FakeConnector().apply { results["networth_snapshot"] = ConnectorCall.Ok("""{"networth":1240000,"owner":"Asha"}""", false) }
        AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, allowAll, debugLog = { logs += it })
            .run(emptyList(), "net worth", settings).toList()
        assertEquals("networth_snapshot shape: {networth:num,owner:str}", logs.single())
    }

    @Test fun handBackIsOnlyOfferedWhenTheConnectorExists() = runBlocking {
        val llm = FakeLlm({ reply("Hi.") })
        AgentLoop(llm, emptyMap(), meter, allowAll).run(emptyList(), "hi", settings).toList()
        assertTrue(llm.requests.single().body["tools"] == null)
    }

    // ---- Public tools run on the phone at Claude's request ----

    @Test fun publicToolRunsOnThePhoneAndOnlyItsResultGoesToClaude() = runBlocking {
        val connector = FakeConnector().apply { results["get_indian_stocks_details"] = ConnectorCall.Ok("""{"price":2950}""", false) }
        val llm = FakeLlm(
            { reply("", stop = "tool_use", content = listOf(toolUse("t1", "get_indian_stocks_details", """{"key":"RELIANCE"}"""))) },
            { reply("About ₹2,950.") },
        )
        val events = AgentLoop(llm, indmoney(connector), meter, allowAll).run(emptyList(), "reliance share price", settings).toList()

        val done = events.last() as AgentEvent.Finished
        assertEquals("About ₹2,950.", done.text)
        assertFalse(done.personal)
        assertEquals("RELIANCE", connector.calls.single().second["key"]!!.jsonPrimitive.content)
        assertTrue(llm.requests[0].body["mcp_servers"] == null)
        val msgs = llm.requests[1].body["messages"]!!.jsonArray
        val result = msgs.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("""{"price":2950}""", result["content"]!!.jsonPrimitive.content)
        assertEquals(200, done.usage.inputTokens)
    }

    @Test fun claudeCannotRunPersonalOrUnknownTools() = runBlocking {
        val audit = RecordingAudit()
        val connector = FakeConnector()
        val llm = FakeLlm(
            { reply("", stop = "tool_use", content = listOf(toolUse("a", "networth_snapshot"), toolUse("b", "delete_everything"))) },
            { reply("Can't see that.") },
        )
        AgentLoop(llm, indmoney(connector), meter, allowAll, audit = audit).run(emptyList(), "reliance share price", settings).toList()
        assertTrue(connector.calls.isEmpty())
        val results = llm.requests[1].body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(results.all { it["is_error"]!!.jsonPrimitive.content == "true" })
        assertEquals(2, audit.events.count { it.outcome == "blocked" })
    }

    @Test fun toolPolicyStillAppliesToPublicTools() = runBlocking {
        val connector = FakeConnector()
        val gate = ScriptedGate { AccessResult.Denied(DenyReason.POLICY, it.map { t -> t.name }) }
        val llm = FakeLlm(
            { reply("", stop = "tool_use", content = listOf(toolUse("t1", "get_indian_stocks_details"))) },
            { reply("That's turned off.") },
        )
        AgentLoop(llm, indmoney(connector), meter, gate).run(emptyList(), "reliance share price", settings).toList()
        assertTrue(connector.calls.isEmpty())
        assertEquals(listOf("get_indian_stocks_details"), gate.calls.single().first)
    }

    @Test fun toolRoundsAreCapped() = runBlocking {
        val script = Array(AgentLoop.MAX_TOOL_ROUNDS + 1) { i ->
            { reply("", stop = "tool_use", content = listOf(toolUse("t$i", "get_indian_stocks_details"))) }
        }
        val llm = FakeLlm(*script)
        val done = AgentLoop(llm, indmoney(), meter, allowAll).run(emptyList(), "reliance share price", settings).toList().last() as AgentEvent.Finished
        assertEquals(AgentLoop.MAX_TOOL_ROUNDS + 1, llm.requests.size)
        assertTrue(done.truncated)
    }

    @Test fun refusalIsAudited() = runBlocking {
        val audit = RecordingAudit()
        val details = JsonObject(mapOf("category" to JsonPrimitive("cyber")))
        AgentLoop(FakeLlm({ reply("", stop = "refusal", details = details) }), emptyMap(), meter, allowAll, audit = audit)
            .run(emptyList(), "hi", settings).toList()
        assertEquals("refused", audit.events.single { it.category == AuditCategory.REQUEST }.outcome)
    }

    // ---- Security gate ----

    private class ScriptedGate(val result: (List<ToolSpec>) -> AccessResult) : AccessController {
        val calls = mutableListOf<Pair<List<String>, AuthMethod?>>()
        override suspend fun authorize(tools: List<ToolSpec>, preferred: AuthMethod?): AccessResult {
            calls += tools.map { it.name } to preferred
            return result(tools)
        }
    }

    @Test fun deniedHandBackUsesNoConnectorOrSavedData() = runBlocking {
        snapshots.save(ToolRegistry.INDMONEY, "networth_snapshot", "TOTAL_100")
        val connector = FakeConnector()
        val gate = ScriptedGate { AccessResult.Denied(DenyReason.CANCELLED, it.map { t -> t.name }) }
        val events = AgentLoop(FakeLlm(handoff()), indmoney(connector), meter, gate, snapshots = snapshots)
            .run(emptyList(), "what's my net worth", settings).toList()
        assertTrue((events.last() as AgentEvent.Failed).error is AgentError.AccessDenied)
        assertTrue(connector.calls.isEmpty())
        assertTrue(events.none { it is AgentEvent.TextDelta })
    }

    @Test fun preEmptedMethodReachesTheGateAndIsNotSentToClaude() = runBlocking {
        val gate = ScriptedGate { AccessResult.Granted(it, AuthMethod.BIOMETRIC, screenOnly = true, sensitive = true) }
        val llm = FakeLlm(handoff())
        AgentLoop(llm, indmoney(), meter, gate).run(emptyList(), "net worth, fingerprint", settings).toList()
        assertEquals(AuthMethod.BIOMETRIC, gate.calls.single().second)
        val question = llm.requests.single().body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("net worth", question)
    }

    @Test fun toolFreeQuestionsNeverReachTheGate() = runBlocking {
        val gate = ScriptedGate { error("must not be called") }
        val events = AgentLoop(FakeLlm({ reply("Hello.") }), indmoney(), meter, gate).run(emptyList(), "hi", settings).toList()
        assertTrue(events.last() is AgentEvent.Finished)
        assertTrue(gate.calls.isEmpty())
    }

    @Test fun authorizedEventComesBeforeAnyReplyText() = runBlocking {
        val gate = ScriptedGate { AccessResult.Granted(it, AuthMethod.BIOMETRIC, screenOnly = true, sensitive = true) }
        val events = AgentLoop(FakeLlm(handoff()), indmoney(), meter, gate).run(emptyList(), "what's my net worth", settings).toList()
        val authIndex = events.indexOfFirst { it is AgentEvent.Authorized }
        val textIndex = events.indexOfFirst { it is AgentEvent.TextDelta }
        assertTrue(authIndex in 0 until textIndex)
        assertEquals(AgentEvent.Authorized(screenOnly = true, sensitive = true), events[authIndex])

        val plain = AgentLoop(FakeLlm({ reply("Hi.") }), emptyMap(), meter, gate).run(emptyList(), "hi", settings).toList()
        assertTrue(plain.none { it is AgentEvent.Authorized })
    }
}
