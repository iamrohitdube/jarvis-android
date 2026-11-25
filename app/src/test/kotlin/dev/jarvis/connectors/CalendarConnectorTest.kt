package dev.jarvis.connectors

import dev.jarvis.agent.CalendarAnswers
import dev.jarvis.agent.DataOutline
import dev.jarvis.agent.Recipe
import dev.jarvis.connectors.phone.CalendarConnector
import dev.jarvis.connectors.phone.CalendarEvent
import dev.jarvis.connectors.phone.CalendarResult
import dev.jarvis.connectors.phone.CalendarSource
import dev.jarvis.testJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarConnectorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    /** Wednesday 25 Nov 2026, 12:00. */
    private val now = ZonedDateTime.of(2026, 11, 25, 12, 0, 0, 0, zone)

    private val standup = CalendarEvent("Standup", "2026-11-25T10:00", "2026-11-25T10:30", location = "Room 4")
    private val review = CalendarEvent("Design review", "2026-11-25T15:00", "2026-11-25T16:00")
    private val dentist = CalendarEvent("Dentist", "2026-11-28T16:00", "2026-11-28T16:30", location = "Smile Clinic")
    private val holiday = CalendarEvent("Holiday", "2026-11-26", "2026-11-27", all_day = true)
    private val all = listOf(standup, review, holiday, dentist)

    private var windows = mutableListOf<Pair<Long, Long>>()
    private val source = CalendarSource { from, to ->
        windows += from to to
        all.filter { e ->
            val start = (if (e.all_day) LocalDateTime.parse(e.start + "T00:00") else LocalDateTime.parse(e.start)).atZone(zone).toInstant().toEpochMilli()
            start in from until to
        }
    }
    private var allowed = true
    private val connector = CalendarConnector(source, testJson, { allowed }, clock = { now.toInstant().toEpochMilli() }, zone = { zone })

    private fun call(tool: String, args: Map<String, String> = emptyMap()): CalendarResult = runBlocking {
        val r = connector.callTool(tool, JsonObject(args.mapValues { JsonPrimitive(it.value) })) as ConnectorCall.Ok
        testJson.decodeFromString(CalendarResult.serializer(), r.text)
    }

    @Test fun todayIsTheWholeLocalDay() {
        val r = call(CalendarConnector.TODAY)
        assertEquals(listOf("Standup", "Design review"), r.events.map { it.title })
        val (from, to) = windows.single()
        assertEquals(now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(24 * 3600_000L, to - from)
    }

    @Test fun upcomingStartsNowAndSearchMatchesTitleOrPlace() {
        assertEquals(listOf("Design review", "Holiday", "Dentist"), call(CalendarConnector.UPCOMING).events.map { it.title })
        assertEquals(listOf("Dentist"), call(CalendarConnector.SEARCH, mapOf("query" to "smile")).events.map { it.title })
        assertTrue(runBlocking { connector.callTool(CalendarConnector.SEARCH, JsonObject(emptyMap())) } is ConnectorCall.Failed)
    }

    @Test fun withoutPermissionItAsksForAccess() = runBlocking {
        allowed = false
        connector.refreshStatus()
        assertEquals(ConnectorStatus.NotConnected, connector.status.first())
        assertEquals(ConnectorCall.NeedsSignIn, connector.callTool(CalendarConnector.TODAY, JsonObject(emptyMap())))
        assertTrue(windows.isEmpty()) // nothing was read
    }

    private fun raw(r: CalendarResult) = testJson.encodeToString(CalendarResult.serializer(), r)

    @Test fun answers() {
        assertEquals(
            "2 events today, 1 still to come:\n• 10:00 AM–10:30 AM Standup (Room 4)\n• 3:00 PM–4:00 PM Design review",
            CalendarAnswers.render("calendar_today", raw(CalendarResult("today", events = listOf(standup, review))), now),
        )
        assertEquals("Nothing on your calendar today.", CalendarAnswers.render("calendar_today", raw(CalendarResult("today")), now))
        val up = CalendarAnswers.render("calendar_upcoming", raw(CalendarResult("next 7 days", events = listOf(review, holiday, dentist))), now)
        assertTrue(up, up.startsWith("Next: Today, 3:00 PM–4:00 PM Design review.\n"))
        assertTrue(up, up.contains("• Tomorrow, all day Holiday"))
        assertTrue(up, up.contains("• Sat 28 Nov, 4:00 PM–4:30 PM Dentist (Smile Clinic)"))
        assertEquals(
            "1 event for \"dentist\":\n• Sat 28 Nov, 4:00 PM–4:30 PM Dentist (Smile Clinic)",
            CalendarAnswers.render("calendar_search", raw(CalendarResult("search", "dentist", listOf(dentist))), now),
        )
    }

    @Test fun recipeOverEventsKeepsTitlesOnThePhone() {
        val data = Json.parseToJsonElement(raw(CalendarResult("next 7 days", events = listOf(review, dentist))))
        val outline = DataOutline.of(data)
        listOf("Design review", "Dentist", "Smile", "2026-11-28").forEach { assertFalse("outline leaks $it:\n$outline", outline.contains(it)) }
        assertTrue(outline, outline.contains("start: date"))
        assertEquals(
            "Your next one is Design review on Wed 25 Nov, 3:00 PM.",
            Recipe.fill("Your next one is {{val events.title}} on {{val events.start}}.", data),
        )
        assertEquals("You have 2 events coming up.", Recipe.fill("You have {{count events}} events coming up.", data))
    }
}
