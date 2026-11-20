package dev.jarvis.data

import dev.jarvis.agent.ChatTurn
import dev.jarvis.agent.DataStamp
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.claude.UsageEntry
import dev.jarvis.claude.UsageMeter
import dev.jarvis.memory.HistoryStore
import dev.jarvis.tempDataStore
import dev.jarvis.testDb
import dev.jarvis.testJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStoreTest {
    private val db = testDb()
    private var now = 1_000L
    private val store = HistoryStore(db.history()) { now++ }

    @After fun tearDown() = db.close()

    @Test fun conversationTitleComesFromFirstMessage() = runBlocking {
        val id = store.startConversation("  What's my\nnet worth today?")
        assertEquals("What's my", store.conversations().first().single { it.id == id }.title)
        assertEquals("x".repeat(59) + "…", HistoryStore.titleFrom("x".repeat(100)))
        assertEquals("New chat", HistoryStore.titleFrom("   "))
    }

    @Test fun contextTurnsSkipFailedRepliesAndTheirQuestions() = runBlocking {
        val id = store.startConversation("hi")
        store.addUserMessage(id, "hi"); store.addAssistantMessage(id, "Hello.")
        store.addUserMessage(id, "net worth"); store.addAssistantMessage(id, "", failed = true)
        store.addUserMessage(id, "net worth"); store.addAssistantMessage(id, "₹12 lakh.")
        store.addUserMessage(id, "pending question") // no reply yet
        val turns = store.contextTurns(id)
        assertEquals(listOf("hi", "Hello.", "net worth", "₹12 lakh."), turns.map { it.text })
        assertEquals(ChatTurn.Role.USER, turns.first().role)
    }

    @Test fun messagesKeepDataStampsAndOrder() = runBlocking {
        val id = store.startConversation("q")
        store.addUserMessage(id, "q")
        val stamp = DataStamp("INDmoney", "networth_snapshot", 42L, cached = true)
        store.addAssistantMessage(id, "a", model = "claude-haiku-5-5", costUsd = 0.0001, dataSources = listOf(stamp))
        val rows = store.messages(id).first()
        assertEquals(listOf("user", "assistant"), rows.map { it.role })
        assertEquals(listOf(stamp), DataStamp.decode(rows[1].dataSources))
    }

    @Test fun searchMatchesTitlesAndMessageTextAndEscapesWildcards() = runBlocking {
        val a = store.startConversation("Portfolio check")
        store.addUserMessage(a, "Portfolio check"); store.addAssistantMessage(a, "Up 3% this week.")
        val b = store.startConversation("Jokes")
        store.addUserMessage(b, "Tell me a joke about 100% effort")
        assertEquals(listOf(a), store.conversations("3% this").first().map { it.id })
        assertEquals(listOf(b), store.conversations("100%").first().map { it.id })
        assertTrue(store.conversations("_").first().isEmpty()) // "_" is literal, not a wildcard
        assertEquals(setOf(a, b), store.conversations("").first().map { it.id }.toSet())
    }

    @Test fun deleteRemovesConversationAndItsMessages() = runBlocking {
        val a = store.startConversation("a"); store.addUserMessage(a, "a")
        val b = store.startConversation("b"); store.addUserMessage(b, "b")
        store.delete(a)
        assertTrue(store.messages(a).first().isEmpty())
        assertEquals(listOf(b), store.conversations().first().map { it.id })
        store.deleteAll()
        assertTrue(store.conversations().first().isEmpty())
        assertTrue(store.messages(b).first().isEmpty())
    }

    @Test fun recentConversationsComeFirst() = runBlocking {
        val a = store.startConversation("a")
        val b = store.startConversation("b")
        store.addUserMessage(a, "again") // touches a
        assertEquals(listOf(a, b), store.conversations().first().map { it.id })
    }
}

class SnapshotStoreTest {
    private val db = testDb()
    private var now = 10 * 60_000L
    private val store = SnapshotStore(db.snapshots()) { now }
    private val netWorth = ToolRegistry.byName("networth_snapshot")!!
    private val holdings = ToolRegistry.byName("networth_holdings")!!
    private val lookup = ToolRegistry.byName("lookup_ind_keys")!!
    private val price = ToolRegistry.byName("get_indian_stocks_details")!!

    @After fun tearDown() = db.close()

    @Test fun freshSnapshotIsReturnedAndHelpersIgnored() = runBlocking {
        store.save("indmoney", "networth_snapshot", "{\"total\":1}", at = now - 60_000)
        val fresh = store.freshFor(listOf(netWorth, lookup), maxAgeMs = 15 * 60_000L)
        assertEquals(listOf("networth_snapshot"), fresh!!.map { it.tool })
    }

    @Test fun staleOrMissingOrDisabledMeansFetchLive() = runBlocking {
        store.save("indmoney", "networth_snapshot", "x", at = now - 20 * 60_000)
        assertNull(store.freshFor(listOf(netWorth), 15 * 60_000L)) // stale
        store.save("indmoney", "networth_snapshot", "x", at = now)
        assertNull(store.freshFor(listOf(netWorth, holdings), 15 * 60_000L)) // holdings missing
        assertNull(store.freshFor(listOf(netWorth), 0)) // reuse turned off
    }

    @Test fun liveMarketToolsAreNeverServedFromCache() = runBlocking {
        store.save("indmoney", "get_indian_stocks_details", "x", at = now)
        assertNull(store.freshFor(listOf(price), 15 * 60_000L))
    }

    @Test fun futureTimestampIsNotTrusted() = runBlocking {
        store.save("indmoney", "networth_snapshot", "x", at = now + 60_000)
        assertNull(store.freshFor(listOf(netWorth), 15 * 60_000L))
    }

    @Test fun contentIsClippedAndClearWorks() = runBlocking {
        store.save("indmoney", "networth_snapshot", "y".repeat(SnapshotStore.MAX_CONTENT_CHARS + 10))
        assertEquals(SnapshotStore.MAX_CONTENT_CHARS, store.all.first().single().content.length)
        store.clear()
        assertTrue(store.all.first().isEmpty())
    }
}

class AuditLogTest {
    private val db = testDb()
    private var now = 400L * 24 * 60 * 60 * 1000
    private val log = AuditLog(db.audit()) { now }

    @After fun tearDown() = db.close()

    @Test fun recordsMetadataNewestFirstAndClipsLongFields() = runBlocking {
        log.record(AuditEvent(AuditCategory.TOOL, "call", "ok", tool = "networth_snapshot", tier = "SENSITIVE"))
        now += 1
        log.record(AuditEvent(AuditCategory.REQUEST, "message", "ok", detail = "z".repeat(500)))
        val rows = log.recent().first()
        assertEquals(listOf("REQUEST", "TOOL"), rows.map { it.category })
        assertEquals(AuditLog.MAX_FIELD, rows[0].detail!!.length)
        assertEquals("SENSITIVE", rows[1].tier)
    }

    @Test fun pruneKeepsOnlyRetentionWindow() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val start = now
        now = start - (AuditLog.RETENTION_DAYS + 1) * day
        log.record(AuditEvent(AuditCategory.DATA, "old", "ok"))
        now = start - day
        log.record(AuditEvent(AuditCategory.DATA, "recent", "ok"))
        now = start
        log.prune()
        assertEquals(listOf("recent"), log.recent().first().map { it.action })
    }
}

class UsageMeterRoomTest {
    private val db = testDb()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun entry(ts: Long, cost: Double) = UsageEntry(ts, "claude-haiku-5-5", "chat", 10, 5, 0, 0, cost, 100, 50, 0)

    @Test fun recordsAndReadsBackInTimeOrder() = runBlocking {
        val meter = UsageMeter(db.usage())
        meter.record(entry(2, 0.2)); meter.record(entry(1, 0.1))
        assertEquals(listOf(1L, 2L), meter.entries.first().map { it.timestampMs })
    }

    @Test fun importsPhaseOneLogAndSkipsGarbage() = runBlocking {
        // DataStore read/remove is a thin wrapper (UsageMeter.importLegacy); the JSON import is tested here.
        // (A file DataStore can't be written twice in one JVM test on Windows: it can't rename over its own file.)
        val legacy = testJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(UsageEntry.serializer()), listOf(entry(5, 0.5)))
        val meter = UsageMeter(db.usage())
        assertEquals(1, meter.importLegacyJson(legacy, testJson))
        assertEquals(0, meter.importLegacyJson("not json", testJson))
        assertEquals(listOf(0.5), meter.entries.first().map { it.costUsd })
    }

    @Test fun legacyImportIsANoOpWhenNothingIsStored() = runBlocking {
        val meter = UsageMeter(db.usage())
        meter.importLegacy(tempDataStore(scope), testJson)
        assertTrue(meter.entries.first().isEmpty())
    }

    @Test fun pruneDropsEntriesOlderThanRetention() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val now = 1000 * day
        val meter = UsageMeter(db.usage()) { now }
        meter.record(entry(now - (UsageMeter.RETENTION_DAYS + 1) * day, 1.0))
        meter.record(entry(now - day, 2.0))
        meter.prune()
        assertNotNull(meter.entries.first().singleOrNull { it.costUsd == 2.0 })
        assertEquals(1, meter.entries.first().size)
    }
}
