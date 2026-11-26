package dev.jarvis.agent

import dev.jarvis.allowAll
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.McpToolDef
import dev.jarvis.data.AppSettings
import dev.jarvis.security.AccessController
import dev.jarvis.tempUsageMeter
import dev.jarvis.testDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PersonIntentTest {
    @Test fun briefRequests() {
        assertEquals("Priya", PersonIntent.target("prep me for my call with Priya"))
        assertEquals("Ravi Kumar", PersonIntent.target("Jarvis, catch me up on Ravi Kumar."))
        assertEquals("mom", PersonIntent.target("what's new with my mom?"))
        assertEquals("Amit", PersonIntent.target("brief me on Amit"))
        assertEquals("Neha", PersonIntent.target("prep me for the meeting with Neha"))
        assertEquals("Priya", PersonIntent.target("show me Priya"))
    }

    @Test fun otherQuestionsAreNotBriefs() {
        listOf(
            "what's up with the weather", "show me my calendar", "show me the latest emails from my bank today please",
            "what is new", "call Priya", "catch me up on my day", "show me 123",
        ).forEach { assertNull(it, PersonIntent.target(it)) }
    }
}

/** A phone source that answers each tool with canned JSON. */
private class Source(override val id: String, private val replies: Map<String, String>, private val signedIn: Boolean = true) : Connector {
    val asked = mutableListOf<Pair<String, JsonObject>>()
    override val displayName = id
    override val status: Flow<ConnectorStatus> = flowOf(ConnectorStatus.Connected)
    override suspend fun callTool(name: String, arguments: JsonObject): ConnectorCall {
        asked += name to arguments
        if (!signedIn) return ConnectorCall.NeedsSignIn
        return replies[name]?.let { ConnectorCall.Ok(it, toolError = false) } ?: ConnectorCall.Failed("no")
    }
    override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> = emptyList()
    override suspend fun test() = ConnectorTestResult.Ok
    override suspend fun signOut() = Unit
}

class PeopleConnectorTest {
    private val now = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val calls = Source("calls", mapOf(
        "calls_with" to """{"range":"30 days","query":"priya","calls":[
            {"name":"Priya Sharma","number":"+915550001001","type":"missed","at":"2026-11-26T09:00","duration_s":0},
            {"name":"Priya Sharma","number":"+915550001001","type":"outgoing","at":"2026-11-25T18:10","duration_s":720}]}""",
        "calls_recent" to """{"range":"7 days","calls":[
            {"name":"Priya Sharma","number":"1","type":"incoming","at":"2026-11-26T09:00","duration_s":60},
            {"name":"number ending 2222","number":"2","type":"missed","at":"2026-11-26T08:00","duration_s":0}]}""",
    ))
    private val chats = Source("chats", mapOf(
        "chats_from" to """{"source":"chats","query":"priya","messages":[
            {"from":"Priya","app":"WhatsApp","at":"2026-11-26T10:05","text":"are you coming?"},
            {"from":"Priya","app":"WhatsApp","at":"2026-11-26T10:01","text":"hey"}]}""",
        "chats_recent" to """{"source":"chats","messages":[{"from":"Amit","app":"WhatsApp","at":"2026-11-26T11:00","text":"lunch","conversation":"Team"}]}""",
    ))
    private val gmail = Source("gmail", mapOf(
        "gmail_search" to """{"query":"priya","messages":[
            {"from":"Priya Sharma <priya@example.com>","subject":"Lunch on Friday?","at":1795000000000,"unread":true},
            {"from":"Newsletter <news@x.example>","subject":"Priya's tips","at":1795000000000,"unread":true}]}""",
    ))
    private val calendar = Source("calendar", mapOf(
        "calendar_search" to """{"range":"search","query":"priya","events":[
            {"title":"1:1 with Priya","start":"2026-11-27T15:00","end":"2026-11-27T15:30","location":"Room 4"}]}""",
    ))
    private val sms = Source("sms", emptyMap(), signedIn = false)
    private val people = PeopleConnector(calls, sms, chats, gmail, calendar, json)

    @Test fun briefGathersEverySourceAndSkipsEmailsThatOnlyMentionThem() = runBlocking {
        val b = people.brief("Priya")
        assertEquals(1, b.emails!!.messages.size)
        assertEquals(listOf("texts"), b.unavailable)
        assertTrue(calls.asked.single().second["query"] == JsonPrimitive("Priya"))
        val text = PeopleAnswers.render(json.encodeToString(PersonBrief.serializer(), b), now)
        assertEquals(
            "Priya Sharma:\n" +
                "• Next meeting: \"1:1 with Priya\" tomorrow 3:00 PM at Room 4\n" +
                "• Calls: 1 missed call, latest today 9:00 AM; last talked yesterday 6:10 PM (12 min)\n" +
                "• WhatsApp: 2 recent, latest today 10:05 AM: \"are you coming?\"\n" +
                "• Email: 1 unread; latest \"Lunch on Friday?\" " + text.substringAfter("Lunch on Friday?\" ").substringBefore("\n") + "\n" +
                "(Couldn't check: texts.)",
            text,
        )
    }

    @Test fun nobodyFound() = runBlocking {
        val empty = PeopleConnector(null, null, null, null, null, json)
        assertEquals("Nothing recent with Zed.", PeopleAnswers.render(json.encodeToString(PersonBrief.serializer(), empty.brief("Zed")), now))
    }

    @Test fun recentPeopleSkipUnknownNumbers() = runBlocking {
        val list = people.recent()
        assertEquals(listOf("Team", "Priya Sharma"), list.map { it.name })
        assertEquals(setOf("calls"), list[1].via)
    }
}

/** "Prep me for my call with Priya" goes through the security gate and never reaches Claude. */
class PeopleAgentTest {
    private val db = testDb()
    @After fun tearDown() = db.close()

    private class NoLlm : LlmClient {
        var calls = 0
        override fun stream(request: dev.jarvis.claude.LlmRequest): Flow<LlmEvent> { calls++; return flowOf() }
        override suspend fun testKey(apiKey: String) = Result.success(Unit)
    }

    @Test fun briefIsGatedAndAnsweredOnThePhone() = runBlocking {
        val llm = NoLlm()
        var gated: List<String> = emptyList()
        val access = AccessController { tools, m -> gated = tools.map { it.name }; allowAll.authorize(tools, m) }
        val people = PeopleConnector(null, null, null, null, null, Json)
        val agent = AgentLoop(llm, mapOf(ToolRegistry.PEOPLE to people), tempUsageMeter(db), access)
        val done = agent.run(emptyList(), "prep me for my call with Zed", AppSettings()).toList().last() as AgentEvent.Finished
        assertEquals("Nothing recent with Zed.", done.text)
        assertEquals(listOf("person_brief"), gated)
        assertEquals(0, llm.calls)
        assertTrue(done.personal)
    }

    @Test fun privateModeBlocksIt() = runBlocking {
        val agent = AgentLoop(NoLlm(), mapOf(ToolRegistry.PEOPLE to PeopleConnector(null, null, null, null, null, Json)), tempUsageMeter(db), allowAll)
        val last = agent.run(emptyList(), "catch me up on Zed", AppSettings(privateMode = true)).toList().last()
        assertFalse(last is AgentEvent.Finished && last.text.contains("Nothing recent"))
    }
}
