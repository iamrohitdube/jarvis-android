package dev.jarvis.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun parseAll(text: String): List<SseEvent> {
        val p = SseParser()
        val out = text.split("\n").mapNotNull { p.feed(it) }.toMutableList()
        p.finish()?.let { out += it }
        return out
    }

    @Test fun parsesEventAndData() {
        val events = parseAll("event: ping\ndata: {\"type\":\"ping\"}\n\n")
        assertEquals(listOf(SseEvent("ping", "{\"type\":\"ping\"}")), events)
    }

    @Test fun joinsMultiLineDataAndIgnoresComments() {
        val events = parseAll(": keep-alive\nevent: x\ndata: a\ndata: b\n\n")
        assertEquals(listOf(SseEvent("x", "a\nb")), events)
    }

    @Test fun dataWithoutSpaceAfterColonAndNoEventName() {
        assertEquals(listOf(SseEvent(null, "{}")), parseAll("data:{}\n\n"))
    }

    @Test fun blankLineWithoutDataEmitsNothing() {
        val p = SseParser()
        assertNull(p.feed("event: lonely"))
        assertNull(p.feed(""))
    }

    @Test fun finishFlushesTrailingEvent() {
        val p = SseParser()
        p.feed("data: tail")
        assertEquals(SseEvent(null, "tail"), p.finish())
    }
}
