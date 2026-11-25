package dev.jarvis.connectors

import dev.jarvis.agent.CallAnswers
import dev.jarvis.agent.DataOutline
import dev.jarvis.agent.Recipe
import dev.jarvis.connectors.phone.CallEntry
import dev.jarvis.connectors.phone.CallLogConnector
import dev.jarvis.connectors.phone.CallLogResult
import dev.jarvis.connectors.phone.CallLogSource
import dev.jarvis.testJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CallLogTest {
    private val now = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private val calls = listOf(
        CallEntry("Mom", "+915550001001", "missed", "2026-11-25T10:05", 0),
        CallEntry("Mom", "+915550001001", "missed", "2026-11-25T09:40", 0),
        CallEntry("", "+915550001002", "missed", "2026-11-24T19:00", 0),
        CallEntry("Mom", "+915550001001", "incoming", "2026-11-24T20:10", 720),
        CallEntry("Rahul Verma", "+915550001003", "outgoing", "2026-11-23T11:00", 95),
    )
    private var asked: Pair<Long, Int>? = null
    private val connector = CallLogConnector(CallLogSource { since, limit -> asked = since to limit; calls }, testJson, { true }, clock = { now.toInstant().toEpochMilli() })

    private fun call(tool: String, query: String? = null): CallLogResult = runBlocking {
        val args = JsonObject(query?.let { mapOf("query" to JsonPrimitive(it)) } ?: emptyMap())
        testJson.decodeFromString(CallLogResult.serializer(), (connector.callTool(tool, args) as ConnectorCall.Ok).text)
    }

    @Test fun missedAndWithFilter() {
        assertEquals(3, call(CallLogConnector.MISSED).calls.size)
        assertEquals(listOf("missed", "missed", "incoming"), call(CallLogConnector.WITH, "mom").calls.map { it.type })
        // By number: the digits given, wherever they are.
        assertEquals(listOf("Rahul Verma"), call(CallLogConnector.WITH, "55500 01003").calls.map { it.name })
        assertEquals(CallLogConnector.RECENT_COUNT, run { call(CallLogConnector.RECENT); asked!!.second })
    }

    private fun raw(r: CallLogResult) = testJson.encodeToString(CallLogResult.serializer(), r)

    @Test fun answers() {
        val missed = CallAnswers.render("calls_missed", raw(CallLogResult("m", calls = calls.filter { it.type == "missed" })), now)
        assertEquals("3 missed calls this week:\n• Mom (2×), last today 10:05 AM\n• number ending 1002, last yesterday 7:00 PM", missed)
        val mom = CallAnswers.render("calls_with", raw(CallLogResult("90", "mom", calls.filter { it.name == "Mom" })), now)
        assertEquals(
            "Last call with Mom: missed, today 10:05 AM. You last talked yesterday 8:10 PM (12 min). 3 calls in 90 days, about 12 min in total.",
            mom,
        )
        assertEquals("No missed calls in the last 7 days.", CallAnswers.render("calls_missed", raw(CallLogResult("m")), now))
    }

    @Test fun recipeKeepsNamesAndNumbersOnThePhone() {
        val data = Json.parseToJsonElement(raw(CallLogResult("last 90 days", "mom", calls.filter { it.name == "Mom" })))
        val outline = DataOutline.of(data)
        listOf("Mom", "5550001001", "2026-11-24").forEach { assertFalse("outline leaks $it:\n$outline", outline.contains(it)) }
        assertTrue(outline, outline.contains("type values: missed, incoming"))
        assertEquals(
            "You talked for 12 min in total over 3 calls.",
            Recipe.fill("You talked for {{sum calls.duration_s}} in total over {{count calls}} calls.", data),
        )
    }
}
