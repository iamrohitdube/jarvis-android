package dev.jarvis.agent

import dev.jarvis.ui.common.dataLabel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ToolCallsTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun pairsUsesWithResults() {
        val content = listOf(
            obj("""{"type":"text","text":"Checking."}"""),
            obj("""{"type":"mcp_tool_use","id":"a","name":"networth_snapshot","server_name":"indmoney","input":{}}"""),
            obj("""{"type":"mcp_tool_result","tool_use_id":"a","is_error":false,"content":[{"type":"text","text":"{\"total\":100}"}]}"""),
            obj("""{"type":"mcp_tool_use","id":"b","name":"networth_holdings","server_name":"indmoney","input":{"asset":"stocks"}}"""),
            obj("""{"type":"mcp_tool_result","tool_use_id":"b","is_error":true,"content":"boom"}"""),
            obj("""{"type":"mcp_tool_use","id":"c","name":"mf_sips","server_name":"indmoney","input":{"note":""}}"""),
        )
        val calls = ToolCalls.extract(content)
        assertEquals(listOf("networth_snapshot", "networth_holdings", "mf_sips"), calls.map { it.name })

        assertEquals("{\"total\":100}", calls[0].resultText)
        assertFalse(calls[0].isError)
        assertTrue(calls[0].hasNoArguments)

        assertTrue(calls[1].isError)
        assertEquals("boom", calls[1].resultText)
        assertFalse(calls[1].hasNoArguments) // a filter argument: not a whole-account snapshot

        assertTrue(calls[2].isError) // no result block came back
        assertNull(calls[2].resultText)
        assertTrue(calls[2].hasNoArguments) // blank strings count as no arguments
    }

    @Test fun dataStampsRoundTripAndIgnoreJunk() {
        val stamps = listOf(DataStamp("INDmoney", "networth_snapshot", 123L, cached = false), DataStamp("IND|money;", "mf_sips", 9L, cached = true))
        val decoded = DataStamp.decode(DataStamp.encode(stamps))
        assertEquals(listOf("INDmoney", "INDmoney"), decoded.map { it.source })
        assertEquals(listOf(false, true), decoded.map { it.cached })
        assertNull(DataStamp.encode(emptyList()))
        assertTrue(DataStamp.decode("garbage;a|b|notanumber|live").isEmpty())
    }

    @Test fun dataLabelSaysLiveOrSaved() {
        val utc = ZoneOffset.UTC
        assertNull(dataLabel(emptyList(), utc))
        val live = dataLabel(listOf(DataStamp("INDmoney", "networth_snapshot", 0L, cached = false)), utc)!!
        assertTrue(live, live.startsWith("INDmoney · updated "))
        val saved = dataLabel(listOf(DataStamp("INDmoney", "networth_snapshot", 0L, cached = true)), utc)!!
        assertTrue(saved, saved.startsWith("INDmoney · saved data from "))
    }

    @Test fun ageLabels() {
        assertEquals("just now", AgentLoop.ageLabel(30_000))
        assertEquals("12 min ago", AgentLoop.ageLabel(12 * 60_000L))
        assertEquals("2 h ago", AgentLoop.ageLabel(125 * 60_000L))
        assertEquals("just now", AgentLoop.ageLabel(-5))
    }
}
