package dev.jarvis.messaging

import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.testDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallIntentTest {
    @Test fun callRequestsAreRecognised() {
        assertEquals("Mom", CallIntent.target("call Mom"))
        assertEquals("mom", CallIntent.target("Call mom please."))
        assertEquals("Ravi Kumar", CallIntent.target("Jarvis, call Ravi Kumar now"))
        assertEquals("Priya", CallIntent.target("can you ring Priya"))
        assertEquals("98765 43210", CallIntent.target("dial 98765 43210"))
        assertEquals("Dad", CallIntent.target("hey jarvis place a call to Dad"))
        assertEquals("mom", CallIntent.target("call my mom"))
    }

    @Test fun otherQuestionsAboutCallsAreNotCalls() {
        listOf(
            "call log", "show my call history", "who called", "missed calls", "call me back", "calls with Mom",
            "what did Rahul say on the call", "call it a day? no", "call him", "",
        ).forEach { assertNull(it, CallIntent.target(it)) }
    }

    @Test fun onlyAnExplicitCallPlacesIt() {
        listOf("call", "Call.", "yes, call", "go ahead", "dial it", "call her").forEach { assertTrue(it, CallPhrase.approves(it)) }
        listOf("yes", "ok", "sure", "don't call", "no call", "cancel", "wait, call", "").forEach { assertFalse(it, CallPhrase.approves(it)) }
        assertTrue(CallPhrase.cancels("no"))
        assertTrue(CallPhrase.cancels("cancel that"))
    }
}

class CallerTest {
    private val db = testDb()
    @After fun tearDown() = db.close()

    private val mom = CallTarget("Mom", "+915550001001", "number ending 1001")
    private val raviK = CallTarget("Ravi Kumar", "+915550001005", "number ending 1005")
    private val raviS = CallTarget("Ravi Singh", "+915550001006", "number ending 1006")
    private var contacts: (String) -> List<CallTarget> = { q -> mapOf("mom" to listOf(mom), "ravi" to listOf(raviK, raviS))[q.lowercase()].orEmpty() }
    private var approve = true
    private val asked = mutableListOf<CallTarget>()
    private val placed = mutableListOf<CallTarget>()
    private var placeResult: Result<String>? = null
    private val audit = mutableListOf<AuditEvent>()

    private val caller = Caller(
        finder = { q -> contacts(q) },
        placer = { t -> placed += t; placeResult ?: Result.success("Calling ${t.name}.") },
        confirm = { t -> asked += t; approve },
        log = db.sentMessages(), audit = AuditSink { audit += it },
    )

    @Test fun confirmedCallIsPlacedAndLogged() = runBlocking {
        assertEquals("Calling Mom.", caller.handle("Mom"))
        assertEquals(listOf(mom), asked)
        assertEquals(listOf(mom), placed)
        val log = db.sentMessages().recent(1).first().single()
        assertEquals("call", log.channel)
        assertEquals("called", log.outcome)
        assertEquals("", log.text)
        assertTrue(audit.any { it.outcome == "ok" })
    }

    @Test fun noConfirmationNoCall() = runBlocking {
        approve = false
        assertEquals("Not calling.", caller.handle("Mom"))
        assertTrue(placed.isEmpty())
    }

    @Test fun severalMatchesAskWhichBeforeConfirming() = runBlocking {
        val reply = caller.handle("Ravi")
        assertTrue(reply, reply.startsWith("Which one? Ravi Kumar (number ending 1005), Ravi Singh (number ending 1006)"))
        assertTrue(asked.isEmpty())
        assertTrue(placed.isEmpty())
    }

    @Test fun unknownNameNumbersAndMissingPermission() = runBlocking {
        assertEquals("I can't find \"Zed\" in your contacts.", caller.handle("Zed"))
        assertEquals("Who should I call?", caller.handle(" "))
        assertEquals("Calling +919876543210.", caller.handle("+91 98765 43210"))
        contacts = { throw SecurityException("no contacts") }
        assertTrue(caller.handle("Mom").contains("contacts"))
    }

    @Test fun failedCallIsLoggedAsFailed() = runBlocking {
        placeResult = Result.failure(IllegalStateException("no SIM"))
        assertEquals("Couldn't call Mom: no SIM.", caller.handle("Mom"))
        assertEquals("failed", db.sentMessages().recent(1).first().single().outcome)
    }

    @Test fun whatsAppNumbersKeepTheirCountryCode() {
        assertEquals("919876543210", WhatsAppOpener.international("+91 98765 43210", null))
        assertNull(WhatsAppOpener.international("+12", null))
    }
}
