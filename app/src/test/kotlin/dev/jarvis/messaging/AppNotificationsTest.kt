package dev.jarvis.messaging

import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.NotificationAnswers
import dev.jarvis.agent.PrivateDataTool
import dev.jarvis.agent.ToolGroup
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.connectors.ConnectorCall
import dev.jarvis.data.db.CapturedNotificationEntity
import dev.jarvis.testDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AppNoticesTest {
    private fun notice(
        pkg: String = "com.bank.app", app: String = "My Bank", title: String = "Account debited", text: String = "Rs 500 spent at Cafe",
        category: String? = null, ongoing: Boolean = false, summary: Boolean = false, secret: Boolean = false,
    ) = AppNotice(app, pkg, title, text, category, ongoing, summary, secret)

    @Test fun bankAlertIsKept() = assertTrue(AppNotices.keep(notice(), "dev.jarvis", emptyList()))

    @Test fun statusChatsCodesAndOwnAppAreSkipped() {
        val own = "dev.jarvis"
        assertFalse("own app", AppNotices.keep(notice(pkg = own), own, emptyList()))
        assertFalse("chat app (handled by chats)", AppNotices.keep(notice(pkg = "com.whatsapp"), own, emptyList()))
        assertFalse("system", AppNotices.keep(notice(pkg = "com.android.systemui"), own, emptyList()))
        assertFalse("music player", AppNotices.keep(notice(ongoing = true), own, emptyList()))
        assertFalse("group summary", AppNotices.keep(notice(summary = true), own, emptyList()))
        assertFalse("secret", AppNotices.keep(notice(secret = true), own, emptyList()))
        listOf("progress", "transport", "navigation", "call", "missed_call", "service", "sys").forEach {
            assertFalse(it, AppNotices.keep(notice(category = it), own, emptyList()))
        }
        assertFalse("empty", AppNotices.keep(notice(title = " ", text = ""), own, emptyList()))
        assertFalse("one-time code", AppNotices.keep(notice(title = "My Bank", text = "Your OTP is 482913. Do not share it."), own, emptyList()))
        assertTrue("email category is kept", AppNotices.keep(notice(category = "email"), own, emptyList()))
    }

    @Test fun ignoreListMatchesAppNameOrPackage() {
        val ignore = AppNotices.ignored(" My Bank , com.shop.app,, ")
        assertEquals(listOf("my bank", "com.shop.app"), ignore)
        assertFalse(AppNotices.keep(notice(app = "My bank"), "dev.jarvis", ignore))
        assertFalse(AppNotices.keep(notice(pkg = "com.shop.app", app = "Shop"), "dev.jarvis", ignore))
        // Whole names only: "Bank" doesn't ignore "My Bank".
        assertTrue(AppNotices.keep(notice(), "dev.jarvis", AppNotices.ignored("Bank")))
    }

    @Test fun repostsAreDeduplicated() {
        val n = notice(title = "Order shipped", text = "Arrives Friday ${System.nanoTime()}")
        assertTrue(AppNotices.firstTime(n))
        assertFalse(AppNotices.firstTime(n))
    }

    @Test fun appNameFallback() {
        assertEquals("Shopping", AppNotices.nameFromPackage("com.example.shopping"))
        assertEquals("Solo", AppNotices.nameFromPackage("solo"))
    }
}

class AlertsConnectorTest {
    private val db = testDb()
    @After fun tearDown() = db.close()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 26, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val hour = 3600_000L
    private var granted = true
    private val connector = AlertsConnector(db.capturedNotifications(), Json, { granted }, clock = { now }, zone = { zone })

    private suspend fun add(app: String, title: String, text: String, at: Long) =
        db.capturedNotifications().insert(CapturedNotificationEntity(app = app, packageName = "p." + app, title = title, text = text, category = null, at = at))

    private fun ok(c: ConnectorCall) = (c as ConnectorCall.Ok).text

    @Test fun recentIsTheLastDayAndSearchCoversThreeDays() = runBlocking {
        add("Amazon", "Out for delivery", "Your parcel arrives today", now - hour)
        add("My Bank", "Debited", "Rs 500 at Cafe", now - 2 * hour)
        add("My Bank", "Credited", "Salary Rs 90,000", now - 30 * hour)
        val recent = Json.decodeFromString(NoticesResult.serializer(), ok(connector.callTool(AlertsConnector.RECENT, JsonObject(emptyMap()))))
        assertEquals(listOf("Amazon", "My Bank"), recent.notices.map { it.app })
        val search = Json.decodeFromString(
            NoticesResult.serializer(),
            ok(connector.callTool(AlertsConnector.SEARCH, JsonObject(mapOf("query" to JsonPrimitive("my bank"))))),
        )
        assertEquals("my bank", search.query)
        assertEquals(listOf("Debited", "Credited"), search.notices.map { it.title })
    }

    @Test fun switchedOffOrNoAccessAsksForAccess() = runBlocking {
        granted = false
        assertEquals(ConnectorCall.NeedsSignIn, connector.callTool(AlertsConnector.RECENT, JsonObject(emptyMap())))
    }
}

class NotificationAnswersTest {
    private val now = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test fun recentIsOneLinePerApp() {
        val raw = """{"notices":[
            {"app":"Amazon","title":"Out for delivery","text":"Your parcel arrives today","at":"2026-11-26T11:00"},
            {"app":"My Bank","title":"Debited","text":"Rs 500 at Cafe","at":"2026-11-26T10:00"},
            {"app":"Amazon","title":"Shipped","text":"","at":"2026-11-26T08:00"}
        ]}"""
        assertEquals(
            "Notifications today:\n• Amazon (2) — Out for delivery: Your parcel arrives today (11:00 AM)\n• My Bank — Debited: Rs 500 at Cafe (10:00 AM)",
            NotificationAnswers.render(AlertsConnector.RECENT, raw, now),
        )
    }

    @Test fun searchAndEmpty() {
        val raw = """{"query":"bank","notices":[{"app":"My Bank","title":"Credited","text":"Credited: Salary","at":"2026-11-25T09:00"}]}"""
        assertEquals("Notifications about \"bank\":\n• My Bank (yesterday 9:00 AM): Credited: Salary", NotificationAnswers.render(AlertsConnector.SEARCH, raw, now))
        assertEquals("No notifications about \"x\" in the last 3 days.", NotificationAnswers.render(AlertsConnector.SEARCH, """{"query":"x"}""", now))
        assertEquals("No notifications from other apps in the last day.", NotificationAnswers.render(AlertsConnector.RECENT, "{}", now))
    }

    @Test fun catalogAndHandBack() {
        val recent = ToolRegistry.byName(AlertsConnector.RECENT)!!
        val search = ToolRegistry.byName(AlertsConnector.SEARCH)!!
        assertEquals(ToolGroup.NOTIFICATIONS, recent.group)
        assertEquals(AuthTier.PERSONAL, recent.tier)
        assertTrue(recent.isPersonal && search.isPersonal)
        assertEquals("query", search.itemArg)
        assertEquals(recent, ToolRegistry.defaultFor(ToolRegistry.ALERTS))
        val kinds = PrivateDataTool.kindsOf(JsonObject(mapOf("kinds" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("notifications_search"))))))
        assertEquals(listOf(search), PrivateDataTool.toolsFor(kinds))
    }
}
