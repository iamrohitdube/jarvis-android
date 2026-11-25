package dev.jarvis.messaging

import dev.jarvis.agent.MessageAnswers
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.testDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SendPhraseTest {
    @Test fun onlySendItSends() {
        listOf("send it", "Yes, send it.", "okay send it now", "JARVIS send it").forEach { assertTrue(it, SendPhrase.approves(it)) }
        listOf("yes", "ok", "sure", "go ahead", "send", "don't send it", "do not send it", "no send it", "wait send it", "").forEach {
            assertFalse(it, SendPhrase.approves(it))
        }
        assertTrue(SendPhrase.cancels("cancel"))
        assertTrue(SendPhrase.cancels("no, don't send"))
    }
}

class MessengerTest {
    private val db = testDb()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); db.close() }

    private val mom = Recipient("Mom", "+915550001001", Channel.SMS, "number ending 2222")
    private val ravi = Recipient("Ravi Kumar", "+915550001005", Channel.SMS, "number ending 0001")
    private val raviS = Recipient("Ravi Singh", "+915550001006", Channel.SMS, "number ending 0002")
    private var contacts = mapOf("mom" to listOf(mom), "ravi" to listOf(ravi, raviS))
    private val sent = mutableListOf<Pair<Recipient, String>>()
    private var sendResult: Result<Unit> = Result.success(Unit)
    private var settings = MessagingSettings(trusted = listOf("Mom"), countdownSeconds = 0)
    private var locked = false
    private val audit = mutableListOf<AuditEvent>()
    private val coordinator = SendCoordinator(confirmTimeoutMs = 400)

    private val messenger = Messenger(
        finder = { _, q, _ -> contacts[q.lowercase()].orEmpty() },
        senders = mapOf(Channel.SMS to ChannelSender { to, text -> sent += to to text; sendResult }),
        coordinator = coordinator, sent = db.sentMessages(), audit = AuditSink { audit += it },
        settings = { settings }, deviceLocked = { locked },
    )

    private fun input(to: String, text: String = "I'll be late", channel: String? = null) =
        JsonObject(buildMap { put("to", JsonPrimitive(to)); put("text", JsonPrimitive(text)); channel?.let { put("channel", JsonPrimitive(it)) } })

    /** Answers the next confirmation the way a user would. */
    private fun answer(action: (SendPrompt) -> Unit) = scope.launch {
        val p = coordinator.prompt.filterNotNull().first { it.stage == SendPrompt.Stage.CONFIRM }
        action(p)
    }

    @Test fun trustedContactCanBeApprovedByVoice() = runBlocking {
        answer { p ->
            assertEquals("I'll be late", p.text) // the FULL draft is shown
            assertTrue(p.voiceAllowed)
            coordinator.approve(byVoice = true)
        }
        assertEquals("Sent to Mom.", messenger.handle(input("Mom")))
        assertEquals(listOf(mom to "I'll be late"), sent)
        assertEquals(1, db.sentMessages().sentSince(0))
        assertTrue(audit.any { it.action == "send" && it.outcome == "ok" })
        assertFalse("message text is never in the audit log", audit.any { it.detail?.contains("late") == true })
    }

    @Test fun untrustedContactIgnoresVoiceAndNeedsATap() = runBlocking {
        contacts = mapOf("ravi kumar" to listOf(ravi))
        answer { coordinator.approve(byVoice = true) } // voice: ignored
        assertEquals("Not sent.", messenger.handle(input("Ravi Kumar"))) // times out
        assertTrue(sent.isEmpty())
        answer { coordinator.approve(byVoice = false) } // a tap works
        assertEquals("Sent to Ravi Kumar.", messenger.handle(input("Ravi Kumar")))
    }

    @Test fun severalMatchesAskWhichBeforeDrafting() = runBlocking {
        val reply = messenger.handle(input("Ravi"))
        assertTrue(reply, reply.startsWith("Which \"Ravi\"? Ravi Kumar (number ending 0001), Ravi Singh (number ending 0002)"))
        assertTrue(audit.none { it.action == "send_draft" }) // no draft was shown
        assertTrue(sent.isEmpty())
    }

    @Test fun unknownRecipientAndMissingText() = runBlocking {
        assertTrue(messenger.handle(input("Zed")).startsWith("I can't find \"Zed\""))
        assertEquals("What should the message say?", messenger.handle(JsonObject(mapOf("to" to JsonPrimitive("Mom")))))
        assertTrue(sent.isEmpty())
    }

    @Test fun offSwitchAndLockedPhoneSendNothing() = runBlocking {
        settings = settings.copy(sendingEnabled = false)
        assertTrue(messenger.handle(input("Mom")).startsWith("Sending messages is turned off"))
        settings = settings.copy(sendingEnabled = true)
        locked = true
        assertTrue(messenger.handle(input("Mom")).startsWith("Unlock your phone"))
        assertTrue(sent.isEmpty())
    }

    @Test fun cancelDuringCountdownSendsNothing() = runBlocking {
        settings = settings.copy(countdownSeconds = 5)
        answer { coordinator.approve(byVoice = false) }
        scope.launch {
            coordinator.prompt.filterNotNull().first { it.stage == SendPrompt.Stage.COUNTDOWN }
            coordinator.cancel()
        }
        assertEquals("Cancelled. Not sent.", messenger.handle(input("Mom")))
        assertTrue(sent.isEmpty())
        assertEquals("cancelled", db.sentMessages().recent(1).first().single().outcome)
    }

    @Test fun hourlyLimitAndFailuresAreLogged() = runBlocking {
        settings = settings.copy(maxPerHour = 1)
        answer { coordinator.approve(byVoice = true) }
        assertEquals("Sent to Mom.", messenger.handle(input("Mom")))
        assertTrue(messenger.handle(input("Mom")).contains("the limit"))
        settings = settings.copy(maxPerHour = 5)
        sendResult = Result.failure(IllegalStateException("no signal"))
        answer { coordinator.approve(byVoice = true) }
        assertEquals("Couldn't send to Mom: no signal.", messenger.handle(input("Mom")))
        assertEquals("failed", db.sentMessages().recent(1).first().single().outcome)
    }
}

class MessageAnswersTest {
    private val now = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))

    @Test fun chatsAreGroupedByConversation() {
        val raw = """{"source":"chats","messages":[
            {"from":"Priya","app":"WhatsApp","at":"2026-11-26T10:05","text":"are you coming?"},
            {"from":"Priya","app":"WhatsApp","at":"2026-11-26T10:01","text":"hey"},
            {"from":"Amit","app":"WhatsApp","at":"2026-11-26T09:00","text":"lunch at 1","conversation":"Team"}
        ]}"""
        assertEquals(
            "Recent chat messages:\n• WhatsApp · Priya (2) — are you coming? (10:05 AM)\n• WhatsApp · Team — Amit: lunch at 1 (9:00 AM)",
            MessageAnswers.render("chats_recent", raw, now),
        )
    }

    @Test fun textsFromSomeone() {
        val raw = """{"source":"sms","query":"hdfc","messages":[{"from":"HDFC Bank","app":"SMS","at":"2026-11-25T18:00","text":"Rs 5000 credited"}]}"""
        assertEquals("From HDFC Bank:\n• yesterday 6:00 PM: Rs 5000 credited", MessageAnswers.render("sms_from", raw, now))
        assertEquals("No recent texts from \"x\".", MessageAnswers.render("sms_from", """{"source":"sms","query":"x"}""", now))
    }

    @Test fun duplicatesAndCodesAreSkipped() {
        val n = ChatNotice("WhatsApp", "com.whatsapp", "Priya", "Priya", "hello there", false)
        assertTrue(ChatNotices.firstTime(n))
        assertFalse(ChatNotices.firstTime(n))
        assertTrue(ChatNotices.isCode("Your OTP is 123456"))
        assertFalse(ChatNotices.isCode("see you at 5"))
    }
}
