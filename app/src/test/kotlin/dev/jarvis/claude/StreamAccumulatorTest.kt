package dev.jarvis.claude

import dev.jarvis.sse
import dev.jarvis.testJson
import dev.jarvis.textStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamAccumulatorTest {
    private fun run(body: String): Pair<List<StreamUpdate>, StreamAccumulator> {
        val acc = StreamAccumulator(testJson)
        val parser = SseParser()
        val updates = body.split("\n").mapNotNull { parser.feed(it) }.flatMap { acc.onEvent(it) }
        return updates to acc
    }

    @Test fun assemblesTextAndUsage() {
        val (updates, acc) = run(textStream("Hello"))
        assertEquals(listOf(StreamUpdate.TextDelta("Hello")), updates)
        val msg = acc.result()
        assertEquals("Hello", msg.text)
        assertEquals("end_turn", msg.stopReason)
        assertEquals("claude-sonnet-5-5", msg.model)
        assertEquals(100, msg.usage.inputTokens)
        assertEquals(20, msg.usage.outputTokens) // message_delta usage overrides message_start
    }

    @Test fun rebuildsMcpToolUseInputAndThinkingSignature() {
        val body = sse(
            "message_start" to """{"type":"message_start","message":{"model":"claude-sonnet-5-5","usage":{"input_tokens":5}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"mcp_tool_use","id":"mcptoolu_1","name":"networth_snapshot","server_name":"indmoney","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"cur"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"rency\":\"INR\"}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"mcp_tool_result","tool_use_id":"mcptoolu_1","is_error":false,"content":[{"type":"text","text":"{}"}]}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":2}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"pause_turn"},"usage":{"output_tokens":9}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val (updates, acc) = run(body)
        assertEquals(StreamUpdate.ToolStarted("networth_snapshot", "indmoney"), updates[0])
        assertEquals(StreamUpdate.ToolFinished(false), updates[1])

        val msg = acc.result()
        assertEquals("pause_turn", msg.stopReason)
        val thinking = msg.content[0]
        assertEquals("hmm", thinking["thinking"]!!.jsonPrimitive.content)
        assertEquals("sig123", thinking["signature"]!!.jsonPrimitive.content)
        val toolUse = msg.content[1]
        assertEquals("INR", toolUse["input"]!!.jsonObject["currency"]!!.jsonPrimitive.content)
        assertEquals("mcp_tool_result", msg.content[2]["type"]!!.jsonPrimitive.content)
    }

    @Test fun unknownBlockTypesArePreservedVerbatim() {
        val body = sse(
            "message_start" to """{"type":"message_start","message":{"model":"m","usage":{}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"fallback","from":{"model":"a"},"to":{"model":"b"}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val msg = run(body).second.result()
        assertEquals("b", msg.content[0]["to"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun errorEventThrowsTypedException() {
        try {
            run(sse("error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
            fail("expected exception")
        } catch (e: StreamErrorEvent) {
            assertEquals("overloaded_error", e.errorType)
        }
    }

    @Test fun incompleteStreamIsReported() {
        val (_, acc) = run(sse("message_start" to """{"type":"message_start","message":{"usage":{}}}"""))
        assertFalse(acc.finished)
        try { acc.result(); fail() } catch (e: StreamProtocolException) { assertTrue(true) }
    }

    @Test fun refusalStopDetailsAreCaptured() {
        val body = sse(
            "message_start" to """{"type":"message_start","message":{"model":"m","usage":{}}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val msg = run(body).second.result()
        assertEquals("refusal", msg.stopReason)
        assertEquals("cyber", msg.stopDetails!!["category"]!!.jsonPrimitive.content)
    }
}
