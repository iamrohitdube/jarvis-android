package dev.jarvis.messaging

import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.data.db.SentMessageDao
import dev.jarvis.data.db.SentMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** How a message goes out. */
enum class Channel(val wire: String, val label: String) {
    SMS("sms", "SMS"),
    /** A reply through another app's notification (WhatsApp, Telegram…): only to conversations with a live notification. */
    CHAT("chat", "chat reply"),
    /** A phone call (D36): reuses the confirmation dialog; never chosen by [of]. */
    CALL("call", "call"),
    /** Gmail (D36): new emails and replies in the same thread. */
    EMAIL("email", "email"),
    ;
    companion object {
        fun of(wire: String?): Channel = when (wire?.lowercase(Locale.ENGLISH)) {
            null, "", "sms", "text", "message" -> SMS
            "email", "e-mail", "mail", "gmail" -> EMAIL
            else -> CHAT // whatsapp, telegram, signal, chat…
        }
    }
}

/** Who a message goes to. [address] is a phone number (SMS) or a notification key (chat reply). */
data class Recipient(
    val name: String,
    val address: String,
    val channel: Channel,
    /** Shown in the confirmation: "number ending 2222", "WhatsApp". */
    val detail: String,
    /** For chat replies: the app's package (whatsapp vs telegram filtering). */
    val app: String = "",
    /** Email only: the subject ("Re: …" for a reply). */
    val subject: String = "",
    /** Email replies: the email being answered. */
    val thread: EmailThread? = null,
)

/** Finds recipients by name or number (contacts for SMS, live chat notifications for replies). */
fun interface RecipientFinder {
    suspend fun find(channel: Channel, query: String, app: String?): List<Recipient>
}

/** Sends on one channel. */
fun interface ChannelSender {
    suspend fun send(to: Recipient, text: String): Result<Unit>
}

data class MessagingSettings(
    /** The "stop everything" switch (CLAUDE.md section 7). */
    val sendingEnabled: Boolean = true,
    /** Trusted names: only these can be approved by voice; anyone else needs a tap on the screen. */
    val trusted: List<String> = emptyList(),
    val maxPerHour: Int = 20,
    val countdownSeconds: Int = 5,
)

/**
 * The exact phrase that sends (CLAUDE.md section 7.2): "send it". A bare "yes", "ok" or "sure" is not enough, and
 * anything negated ("don't send it") never counts.
 */
object SendPhrase {
    private val NEGATION = Regex("\\b(don'?t|do not|never|not|no|stop|cancel|wait|hold)\\b")

    fun approves(heard: String): Boolean {
        val t = heard.lowercase(Locale.ENGLISH).replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return Regex("\\bsend it\\b").containsMatchIn(t) && !NEGATION.containsMatchIn(t)
    }

    fun cancels(heard: String): Boolean =
        Regex("\\b(cancel|stop|don'?t send|do not send|no)\\b").containsMatchIn(heard.lowercase(Locale.ENGLISH))
}

/** What the confirmation UI shows. Nothing is sent while this is open. */
data class SendPrompt(
    val id: Long,
    val stage: Stage,
    val recipient: Recipient,
    val text: String,
    /** Trusted recipients can be approved by saying "send it"; others need a tap. */
    val voiceAllowed: Boolean,
    val secondsLeft: Int = 0,
) {
    enum class Stage { CONFIRM, COUNTDOWN }
}

/** Bridge between the send flow and the UI / voice (like the PIN dialog's coordinator). One prompt at a time. */
class SendCoordinator(private val confirmTimeoutMs: Long = 60_000) {
    private val _prompt = MutableStateFlow<SendPrompt?>(null)
    val prompt: StateFlow<SendPrompt?> = _prompt
    private val answers = KChannel<Answer>(KChannel.UNLIMITED)
    private var nextId = 0L

    private sealed interface Answer {
        data class Approve(val byVoice: Boolean) : Answer
        data object Cancel : Answer
    }

    /** "Send it" tapped (byVoice = false) or said (byVoice = true). */
    fun approve(byVoice: Boolean) { answers.trySend(Answer.Approve(byVoice)) }
    fun cancel() { answers.trySend(Answer.Cancel) }

    /** Shows the full draft and waits. Silence or timeout = not sent. */
    suspend fun confirm(to: Recipient, text: String, voiceAllowed: Boolean): Boolean {
        while (answers.tryReceive().isSuccess) Unit
        _prompt.value = SendPrompt(++nextId, SendPrompt.Stage.CONFIRM, to, text, voiceAllowed)
        try {
            return withTimeoutOrNull(confirmTimeoutMs) { awaitDecision(voiceAllowed) } ?: false
        } finally {
            _prompt.value = null
        }
    }

    /** Voice approval counts only when [voiceAllowed] (trusted recipient); a tap always counts. */
    private suspend fun awaitDecision(voiceAllowed: Boolean): Boolean {
        while (true) {
            when (val a = answers.receive()) {
                is Answer.Approve -> if (!a.byVoice || voiceAllowed) return true
                Answer.Cancel -> return false
            }
        }
    }

    private suspend fun awaitCancel(): Boolean {
        while (true) if (answers.receive() == Answer.Cancel) return true
    }

    /** "Sending in 5…" with Cancel. Returns false if cancelled. */
    suspend fun countdown(to: Recipient, text: String, seconds: Int): Boolean {
        while (answers.tryReceive().isSuccess) Unit
        try {
            for (left in seconds downTo 1) {
                _prompt.value = SendPrompt(++nextId, SendPrompt.Stage.COUNTDOWN, to, text, voiceAllowed = false, secondsLeft = left)
                val cancelled = withTimeoutOrNull(1_000) { awaitCancel() } == true
                if (cancelled) return false
            }
            return true
        } finally {
            _prompt.value = null
        }
    }
}

/**
 * Sends a message Claude drafted, under CLAUDE.md section 7: resolve the recipient (ask which if several match,
 * before drafting), show and read the FULL draft, require "send it" (voice only for trusted recipients, otherwise a
 * tap), a countdown with cancel, a per-hour limit and an off switch, and a log of every send. Returns the reply text.
 */
class Messenger(
    private val finder: RecipientFinder,
    private val senders: Map<Channel, ChannelSender>,
    private val coordinator: SendCoordinator,
    private val sent: SentMessageDao,
    private val audit: AuditSink,
    private val settings: suspend () -> MessagingSettings,
    /** True while the phone is locked: sending needs the JARVIS screen. */
    private val deviceLocked: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun handle(input: JsonObject?): String = mutex.withLock {
        val s = settings()
        if (!s.sendingEnabled) return@withLock "Sending messages is turned off (Settings → Messaging)."
        if (deviceLocked()) return@withLock "Unlock your phone and open JARVIS to send messages."
        val channel = Channel.of(input.str("channel"))
        val app = input.str("channel")?.lowercase(Locale.ENGLISH)?.takeIf { channel == Channel.CHAT && it != "chat" }
        val to = input.str("to")?.take(80) ?: return@withLock "Who should I send it to?"
        val text = input.str("text")?.take(if (channel == Channel.EMAIL) MAX_EMAIL else MAX_TEXT) ?: return@withLock "What should the message say?"
        // Email: "reply" answers their latest email in its thread; otherwise a new email.
        val reply = channel == Channel.EMAIL && input.str("reply")?.lowercase(Locale.ENGLISH) == "true"
        val sender = senders[channel] ?: return@withLock "I can't send ${channel.label}s on this phone."

        val now = clock()
        val count = sent.sentSince(now - HOUR)
        if (count >= s.maxPerHour) {
            audit(AuditEvent(AuditCategory.TOOL, "send", "rate_limited", tool = SEND_TOOL))
            return@withLock "That's $count messages this hour, the limit. Not sent."
        }

        val matches = try {
            finder.find(channel, to, if (channel == Channel.EMAIL) (if (reply) REPLY else null) else app)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            return@withLock "I need access to your contacts for that. Allow it in Settings → Phone data."
        } catch (e: Exception) {
            return@withLock "Couldn't look up \"$to\": ${e.message ?: "unknown error"}"
        }
        when {
            matches.isEmpty() && channel == Channel.EMAIL -> return@withLock if (reply) "I can't find an email from \"$to\" in your inbox."
                else "I can't find an email address for \"$to\". Say the address, or add it to their contact."
            matches.isEmpty() -> return@withLock if (channel == Channel.SMS) "I can't find \"$to\" in your contacts."
                else "I can't find \"$to\": no open ${app ?: "chat"} notification from them, and no contact of that name for WhatsApp."
            matches.size > 1 -> return@withLock "Which \"$to\"? " + matches.take(4).joinToString(", ") { "${it.name} (${it.detail})" } +
                ". Say the full name."
        }
        val recipient = matches.single().let { r ->
            if (channel == Channel.EMAIL && r.thread == null) r.copy(subject = input.str("subject")?.let(EmailComposer::oneLine) ?: EmailComposer.subjectFrom(text)) else r
        }
        val trusted = s.trusted.any { it.isNotBlank() && recipient.name.equals(it.trim(), ignoreCase = true) }
        audit(AuditEvent(AuditCategory.TOOL, "send_draft", "shown", tool = SEND_TOOL, detail = channel.wire))

        if (!coordinator.confirm(recipient, text, voiceAllowed = trusted)) {
            audit(AuditEvent(AuditCategory.TOOL, "send", "not_confirmed", tool = SEND_TOOL, detail = channel.wire))
            return@withLock "Not sent."
        }
        val approvedAt = clock()
        val id = sent.insert(SentMessageEntity(channel = channel.wire, recipient = recipient.name, text = text, approvedAt = approvedAt, sentAt = null, outcome = "approved"))
        if (!coordinator.countdown(recipient, text, s.countdownSeconds)) {
            sent.finish(id, null, "cancelled")
            audit(AuditEvent(AuditCategory.TOOL, "send", "cancelled", tool = SEND_TOOL, detail = channel.wire))
            return@withLock "Cancelled. Not sent."
        }
        val result = try { sender.send(recipient, text) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        return@withLock if (result.isSuccess) {
            sent.finish(id, clock(), if (WhatsAppOpener.isNewChat(recipient)) "opened" else "sent")
            audit(AuditEvent(AuditCategory.TOOL, "send", "ok", tool = SEND_TOOL, detail = channel.wire))
            if (WhatsAppOpener.isNewChat(recipient)) "WhatsApp is open with your message to ${recipient.name}: tap send."
            else "Sent to ${recipient.name}."
        } else {
            sent.finish(id, null, "failed")
            audit(AuditEvent(AuditCategory.TOOL, "send", "error", tool = SEND_TOOL, detail = channel.wire))
            "Couldn't send to ${recipient.name}: ${result.exceptionOrNull()?.message ?: "unknown error"}."
        }
    }

    private suspend fun audit(e: AuditEvent) = audit.record(e)

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SEND_TOOL = "send_message"
        const val MAX_TEXT = 1000
        const val MAX_EMAIL = 4000
        /** Passed to the finder as the "app" for email replies. */
        const val REPLY = "reply"
        private const val HOUR = 3600_000L
    }
}
