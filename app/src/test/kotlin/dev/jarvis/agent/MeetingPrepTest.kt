package dev.jarvis.agent

import dev.jarvis.connectors.Connector
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.connectors.ConnectorStatus
import dev.jarvis.connectors.ConnectorTestResult
import dev.jarvis.connectors.McpToolDef
import dev.jarvis.connectors.phone.CalendarEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MeetingPrepTest {
    @Test fun namesFromTitles() {
        assertEquals(listOf("Priya"), MeetingPrep.names("1:1 with Priya"))
        assertEquals(listOf("Ravi Kumar"), MeetingPrep.names("Call with Ravi Kumar re budget"))
        assertEquals(listOf("Priya", "Rohit"), MeetingPrep.names("Priya / Rohit sync"))
        assertEquals(listOf("Amit", "Neha"), MeetingPrep.names("Amit <> Neha"))
        assertEquals(listOf("Sam", "Alex"), MeetingPrep.names("Coffee with Sam & Alex"))
        assertEquals(listOf("Priya"), MeetingPrep.names("Interview with Priya (Zoom)"))
    }

    @Test fun titlesWithoutPeople() {
        listOf("Weekly team sync", "Sprint planning", "All hands", "Q4 review 2026", "Dentist", "lunch").forEach {
            assertTrue(it, MeetingPrep.names(it).isEmpty())
        }
    }

    @Test fun onlyTimedMeetingsSoonAndOnce() {
        val now = LocalDateTime.of(2026, 11, 26, 14, 0)
        val soon = CalendarEvent("1:1 with Priya", "2026-11-26T14:20", "2026-11-26T14:50")
        val later = CalendarEvent("Call with Ravi", "2026-11-26T15:00", "2026-11-26T15:30")
        val past = CalendarEvent("Standup", "2026-11-26T13:50", "2026-11-26T14:05")
        val allDay = CalendarEvent("Offsite", "2026-11-26", "2026-11-27", all_day = true)
        assertEquals(listOf(soon), MeetingPrep.due(listOf(soon, later, past, allDay), now, emptySet()))
        assertTrue(MeetingPrep.due(listOf(soon), now, setOf(MeetingPrep.key(soon))).isEmpty())
        assertEquals("in 20 min", MeetingPrep.startsIn(soon, now))
    }

    @Test fun voicePhrases() {
        listOf("prep me for my next meeting", "Jarvis, brief me on the next meeting.", "prepare me for my upcoming call", "what's my next meeting about?")
            .forEach { assertTrue(it, MeetingPrep.wantsNextMeeting(it)) }
        listOf("what's my next meeting", "prep me for my call with Priya", "next meeting").forEach { assertFalse(it, MeetingPrep.wantsNextMeeting(it)) }
    }
}

class MeetingPrepBriefTest {
    private val now = ZonedDateTime.of(2026, 11, 26, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Cal(private val upcoming: String) : Connector {
        override val id = "calendar"
        override val displayName = "Calendar"
        override val status: Flow<ConnectorStatus> = flowOf(ConnectorStatus.Connected)
        override suspend fun callTool(name: String, arguments: JsonObject) = when (name) {
            "calendar_upcoming" -> ConnectorCall.Ok(upcoming, toolError = false)
            else -> ConnectorCall.Ok("""{"range":"search","events":[]}""", toolError = false)
        }
        override suspend fun toolDefinitions(names: Collection<String>): List<McpToolDef> = emptyList()
        override suspend fun test() = ConnectorTestResult.Ok
        override suspend fun signOut() = Unit
    }

    private val calendar = Cal(
        """{"range":"next 7 days","events":[
            {"title":"Offsite","start":"2026-11-26","end":"2026-11-27","all_day":true},
            {"title":"1:1 with Priya","start":"2026-11-26T14:20","end":"2026-11-26T14:50","location":"Room 4"},
            {"title":"Sprint planning","start":"2026-11-26T16:00","end":"2026-11-26T17:00"}]}""",
    )

    @Test fun nextMeetingWithItsPeople() = runBlocking {
        val people = PeopleConnector(null, null, null, null, calendar, json)
        val preps = people.nextMeetings(now.toLocalDateTime(), withinMinutes = 24 * 60)
        assertEquals(listOf("1:1 with Priya", "Sprint planning"), preps.map { it.event!!.title })
        assertEquals(listOf("Priya"), preps[0].people.map { it.name })
        assertTrue(preps[1].people.isEmpty())
        val text = PeopleAnswers.renderMeeting(json.encodeToString(MeetingPrepResult.serializer(), preps[0]), now)
        assertEquals("Next: \"1:1 with Priya\" today 2:20 PM at Room 4.\nPriya: nothing recent.", text)
        assertEquals(
            "Next: \"Sprint planning\" today 4:00 PM.\nNo one named in the title, so no people brief.",
            PeopleAnswers.renderMeeting(json.encodeToString(MeetingPrepResult.serializer(), preps[1]), now),
        )
        assertEquals("No meetings in the next 24 hours.", PeopleAnswers.renderMeeting("{}", now))
    }

    @Test fun cardJobOnlyGetsDueMeetings() = runBlocking {
        val people = PeopleConnector(null, null, null, null, calendar, json)
        val nowLocal = now.toLocalDateTime()
        val due = people.nextMeetings(nowLocal, MeetingPrep.LEAD_MINUTES) { MeetingPrep.due(it, nowLocal, emptySet()) }
        assertEquals(listOf("1:1 with Priya"), due.map { it.event!!.title })
    }
}
