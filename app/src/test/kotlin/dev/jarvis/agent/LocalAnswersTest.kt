package dev.jarvis.agent

import dev.jarvis.allowAll
import dev.jarvis.claude.LlmClient
import dev.jarvis.claude.LlmEvent
import dev.jarvis.claude.LlmRequest
import dev.jarvis.data.AppSettings
import dev.jarvis.tempUsageMeter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalAnswersTest {
    private val now = ZonedDateTime.of(2026, 11, 21, 13, 5, 0, 0, ZoneId.of("Asia/Kolkata"))
    private val phone = PhoneInfo(76, charging = true)

    @Test fun timeDateAndBatteryAreAnsweredOnThePhone() {
        listOf("What's the time?", "what time is it", "Hey Jarvis, what time is it now", "time please").forEach {
            assertEquals(it, "It's 1:05 PM.", LocalAnswers.answer(it, now, phone))
        }
        assertEquals("It's Saturday, 21 November.", LocalAnswers.answer("what's today's date", now, phone))
        assertEquals("It's Saturday, 21 November.", LocalAnswers.answer("What day is it?", now, phone))
        assertEquals("Battery's at 76%, charging.", LocalAnswers.answer("how much battery do I have", now, phone))
        assertEquals("Battery's at 40%.", LocalAnswers.answer("battery", now, PhoneInfo(40, false)))
    }

    @Test fun anythingElseGoesToClaude() {
        listOf(
            "what time is it in London", "set a timer for 5 minutes", "how much time until my meeting",
            "what's my net worth", "is the battery of a Tesla good", "what date is Diwali",
        ).forEach { assertNull(it, LocalAnswers.answer(it, now, phone)) }
        assertNull(LocalAnswers.answer("battery", now, null)) // no battery info available
    }

    @Test fun localAnswersNeverCallClaudeAndCostNothing() = runBlocking {
        var calls = 0
        val llm = object : LlmClient {
            override fun stream(request: LlmRequest): Flow<LlmEvent> { calls++; return emptyFlow() }
            override suspend fun testKey(apiKey: String) = Result.success(Unit)
        }
        val meter = tempUsageMeter()
        val events = AgentLoop(llm, emptyMap(), meter, allowAll, clock = { now.toInstant().toEpochMilli() }, zone = now.zone,
            phoneInfo = { phone }).run(emptyList(), "what's the time", AppSettings()).toList()
        val done = events.last() as AgentEvent.Finished
        assertEquals("It's 1:05 PM.", done.text)
        assertEquals(0.0, done.costUsd!!, 0.0)
        assertEquals(0, calls)
        assertTrue(meter.entries.first().isEmpty())
    }
}
