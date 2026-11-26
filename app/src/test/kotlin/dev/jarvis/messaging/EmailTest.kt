package dev.jarvis.messaging

import dev.jarvis.connectors.google.GmailApi
import dev.jarvis.connectors.google.GoogleTokenSource
import dev.jarvis.data.AuditSink
import dev.jarvis.testDb
import dev.jarvis.testJson
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class EmailComposerTest {
    private fun decode(raw: String) = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
    private fun body(message: String) =
        String(Base64.getMimeDecoder().decode(message.substringAfter("\r\n\r\n")), Charsets.UTF_8)

    @Test fun newEmail() {
        val m = decode(EmailComposer.raw("Priya <priya@example.com>", "Lunch", "See you at 1.\nThanks"))
        assertTrue(m, m.startsWith("To: priya@example.com\r\nSubject: Lunch\r\n"))
        assertTrue(m.contains("Content-Type: text/plain; charset=\"UTF-8\""))
        assertFalse(m.contains("In-Reply-To"))
        assertEquals("See you at 1.\nThanks", body(m))
    }

    @Test fun replyKeepsTheThread() {
        val thread = EmailThread("t1", "<abc@mail.example>", "<root@mail.example>", "Lunch on Friday?")
        val m = decode(EmailComposer.raw("priya@example.com", EmailComposer.replySubject("Lunch on Friday?"), "Yes", thread))
        assertTrue(m.contains("Subject: Re: Lunch on Friday?\r\n"))
        assertTrue(m.contains("In-Reply-To: <abc@mail.example>\r\n"))
        assertTrue(m.contains("References: <root@mail.example> <abc@mail.example>\r\n"))
        assertEquals("Re: Hello", EmailComposer.replySubject("RE: Hello").replace("RE:", "Re:"))
    }

    @Test fun headersCannotBeInjectedAndUnicodeIsEncoded() {
        val m = decode(EmailComposer.raw("a@b.co", "Hi\r\nBcc: evil@x.com", "body"))
        assertFalse(m.contains("\r\nBcc:"))
        val u = decode(EmailComposer.raw("a@b.co", "₹500 paid", "body"))
        assertTrue(u.contains("Subject: =?UTF-8?B?"))
        assertTrue(runCatching { EmailComposer.raw("not an address", "s", "b") }.isFailure)
    }

    @Test fun namesAndSubjects() {
        assertEquals("priya@example.com", EmailComposer.addressOf("\"Priya Sharma\" <priya@example.com>"))
        assertEquals("Priya Sharma", EmailComposer.nameOf("\"Priya Sharma\" <priya@example.com>"))
        assertEquals("hr", EmailComposer.nameOf("hr@acme.example"))
        assertEquals("I will join the call at…", EmailComposer.subjectFrom("I will join the call at 3 tomorrow"))
        assertEquals("(no subject)", EmailComposer.subjectFrom(" "))
    }
}

/** Reply lookup and sending against a fake Gmail. */
class EmailGmailTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val sends = CopyOnWriteArrayList<String>()

    private class Tokens(vararg t: String?) : GoogleTokenSource {
        val queue = ArrayDeque(t.toList())
        val cleared = mutableListOf<String>()
        override suspend fun accessToken(): String? = queue.removeFirstOrNull()
        override suspend fun clearToken(token: String) { cleared += token }
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val auth = request.headers["Authorization"]
                val path = request.url.encodedPath
                if (path.endsWith("/send")) {
                    if (auth != "Bearer send-ok") return MockResponse.Builder().code(401).build()
                    sends += request.body!!.utf8()
                    return ok("""{"id":"new1"}""")
                }
                if (auth != "Bearer read") return MockResponse.Builder().code(401).build()
                if (path == "/gmail/v1/users/me/messages") {
                    val q = request.url.queryParameter("q").orEmpty().lowercase()
                    return when {
                        "priya" in q -> ok("""{"messages":[{"id":"m1"},{"id":"m2"}]}""")
                        "ravi" in q -> ok("""{"messages":[{"id":"r1"},{"id":"r2"}]}""")
                        else -> ok("""{}""")
                    }
                }
                val id = path.substringAfterLast('/')
                val (from, subject, at) = when (id) {
                    "m1" -> Triple("Priya Sharma <priya@example.com>", "Lunch on Friday?", 200)
                    "m2" -> Triple("Priya Sharma <priya@example.com>", "Old thread", 100)
                    "r1" -> Triple("Ravi Kumar <ravi.k@example.com>", "Report", 300)
                    else -> Triple("Ravi Singh <ravi.s@example.com>", "Invoice", 250)
                }
                return ok(
                    """{"id":"$id","threadId":"t-$id","internalDate":"$at","payload":{"headers":[
                    {"name":"From","value":"$from"},{"name":"Subject","value":"$subject"},
                    {"name":"Message-ID","value":"<$id@mail.example>"},{"name":"References","value":""}]}}"""
                )
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private val api by lazy { GmailApi(OkHttpClient(), testJson, server.url("/").toString().trimEnd('/')) }

    @Test fun replyFindsTheNewestEmailAndItsThread() = runBlocking {
        val finder = EmailRecipientFinder({ emptyList() }, Tokens("read"), api)
        val r = finder.find("Priya", reply = true).single()
        assertEquals("Priya Sharma", r.name)
        assertEquals("priya@example.com", r.address)
        assertEquals("Re: Lunch on Friday?", r.subject)
        assertEquals("t-m1", r.thread!!.threadId)
        assertEquals("<m1@mail.example>", r.thread!!.messageId)
        assertTrue(requests.first().url.queryParameter("q")!!.startsWith("in:inbox from:(Priya)"))
    }

    @Test fun twoSendersMeansAskWhich() = runBlocking {
        val finder = EmailRecipientFinder({ emptyList() }, Tokens("read"), api)
        assertEquals(listOf("Ravi Kumar", "Ravi Singh"), finder.find("ravi", reply = true).map { it.name })
    }

    @Test fun newEmailUsesContactsThenPastSenders() = runBlocking {
        val withContact = EmailRecipientFinder({ listOf("Priya" to "p@home.example") }, Tokens("read"), api)
        assertEquals("p@home.example", withContact.find("Priya", reply = false).single().address)
        val fromGmail = EmailRecipientFinder({ emptyList() }, Tokens("read"), api)
        val r = fromGmail.find("priya", reply = false).single()
        assertEquals("priya@example.com", r.address)
        assertNull(r.thread)
        assertEquals("x@y.example", fromGmail.find("x@y.example", reply = false).single().address)
    }

    @Test fun sendPostsRawWithThreadAndRetriesOnce() = runBlocking {
        val tokens = Tokens("stale", "send-ok")
        val sender = EmailSender(tokens, api)
        val to = Recipient("Priya", "priya@example.com", Channel.EMAIL, "priya@example.com", subject = "Re: Lunch",
            thread = EmailThread("t-m1", "<m1@mail.example>", "", "Lunch"))
        assertTrue(sender.send(to, "Yes, see you").isSuccess)
        assertEquals(listOf("stale"), tokens.cleared)
        val body = testJson.parseToJsonElement(sends.single()).jsonObject
        assertEquals("t-m1", body["threadId"]!!.jsonPrimitive.content)
        val message = String(Base64.getUrlDecoder().decode(body["raw"]!!.jsonPrimitive.content))
        assertTrue(message.contains("In-Reply-To: <m1@mail.example>"))
    }

    @Test fun withoutTheSendGrantItSaysHowToAllowIt() = runBlocking {
        val r = EmailSender(Tokens(null), api).send(Recipient("P", "p@x.example", Channel.EMAIL, "p@x.example"), "hi")
        assertEquals(EmailSender.ALLOW, r.exceptionOrNull()?.message)
    }
}

/** The email path through the confirmation flow (CLAUDE.md section 7 rules apply to email too). */
class EmailMessengerTest {
    private val db = testDb()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); db.close() }

    private val coordinator = SendCoordinator(confirmTimeoutMs = 400)
    private val sent = mutableListOf<Pair<Recipient, String>>()
    private var lastMode: String? = "unset"
    private val priya = Recipient("Priya", "priya@example.com", Channel.EMAIL, "priya@example.com")
    private val messenger = Messenger(
        finder = { channel, _, app ->
            lastMode = app
            if (channel != Channel.EMAIL) emptyList()
            else if (app == Messenger.REPLY) listOf(priya.copy(subject = "Re: Lunch", thread = EmailThread("t", "<m>", "", "Lunch")))
            else listOf(priya)
        },
        senders = mapOf(Channel.EMAIL to ChannelSender { to, text -> sent += to to text; Result.success(Unit) }),
        coordinator = coordinator, sent = db.sentMessages(), audit = AuditSink { },
        settings = { MessagingSettings(trusted = listOf("Priya"), countdownSeconds = 0) }, deviceLocked = { false },
    )

    private fun input(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

    private fun approveWhenAsked() = scope.launch {
        coordinator.prompt.filterNotNull().first()
        coordinator.approve(byVoice = true)
    }

    @Test fun newEmailGetsTheGivenSubject() = runBlocking {
        approveWhenAsked()
        assertEquals("Sent to Priya.", messenger.handle(input("channel" to "email", "to" to "Priya", "subject" to "Friday", "text" to "Lunch at 1?")))
        assertNull(lastMode)
        assertEquals("Friday", sent.single().first.subject)
    }

    @Test fun replyKeepsReSubjectAndThread() = runBlocking {
        approveWhenAsked()
        messenger.handle(input("channel" to "gmail", "to" to "Priya", "reply" to "true", "subject" to "ignored", "text" to "Yes"))
        assertEquals(Messenger.REPLY, lastMode)
        assertEquals("Re: Lunch", sent.single().first.subject)
        assertEquals("t", sent.single().first.thread!!.threadId)
    }

    @Test fun notConfirmedIsNotSent() = runBlocking {
        assertEquals("Not sent.", messenger.handle(input("channel" to "email", "to" to "Priya", "text" to "hi")))
        assertTrue(sent.isEmpty())
    }
}
