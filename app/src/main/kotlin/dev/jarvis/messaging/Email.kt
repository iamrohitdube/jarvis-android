package dev.jarvis.messaging

import dev.jarvis.connectors.google.GmailApi
import dev.jarvis.connectors.google.GmailAuthException
import dev.jarvis.connectors.google.GoogleTokenSource
import java.util.Base64
import java.util.Locale

/** The email a reply answers (D36): keeps the reply in the same Gmail conversation for everyone. */
data class EmailThread(val threadId: String, val messageId: String, val references: String, val originalSubject: String)

/** Builds the RFC 2822 text Gmail's send call takes (plain text, UTF-8). Pure, so it is unit-tested. */
object EmailComposer {
    private val ADDRESS = Regex("^[^\\s<>@,;\"]+@[^\\s<>@,;\"]+\\.[^\\s<>@,;\"]+$")

    fun isAddress(s: String) = ADDRESS.matches(s.trim())

    /** "Priya Sharma <priya@example.com>" -> "priya@example.com"; a bare address stays as it is. */
    fun addressOf(from: String): String = from.substringAfterLast('<', from).substringBefore('>').trim().trim('"')

    /** "\"Priya Sharma\" <priya@example.com>" -> "Priya Sharma"; falls back to the part before @. */
    fun nameOf(from: String): String {
        val name = from.substringBefore('<').trim().trim('"').trim()
        return name.takeIf { it.isNotEmpty() && '@' !in it } ?: addressOf(from).substringBefore('@')
    }

    fun replySubject(subject: String): String {
        val s = oneLine(subject)
        return if (s.lowercase(Locale.ENGLISH).startsWith("re:")) s else "Re: $s".trimEnd()
    }

    /** A subject for a new email when none was given: the first few words of the message. */
    fun subjectFrom(text: String): String {
        val words = oneLine(text).split(' ').filter { it.isNotBlank() }
        return words.take(6).joinToString(" ").let { if (words.size > 6) "$it…" else it }.ifBlank { "(no subject)" }
    }

    /** No line breaks in headers (header injection), clipped. */
    fun oneLine(s: String) = s.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim().take(200)

    private fun header(value: String): String =
        if (value.all { it.code in 32..126 }) value
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="

    /** The whole message, base64url encoded (Gmail's `raw`). */
    fun raw(to: String, subject: String, body: String, thread: EmailThread? = null): String {
        val address = addressOf(to)
        require(isAddress(address)) { "That isn't an email address" }
        val lines = mutableListOf(
            "To: $address",
            "Subject: " + header(oneLine(subject)),
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=\"UTF-8\"",
            "Content-Transfer-Encoding: base64",
        )
        if (thread != null && thread.messageId.isNotBlank()) {
            val id = oneLine(thread.messageId)
            lines += "In-Reply-To: $id"
            lines += "References: " + (oneLine(thread.references) + " " + id).trim()
        }
        val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
        val message = lines.joinToString("\r\n") + "\r\n\r\n" + encodedBody
        return Base64.getUrlEncoder().encodeToString(message.toByteArray(Charsets.UTF_8))
    }
}

/** Runs [block] with a Gmail token, retrying once with a fresh one if Gmail rejects it. */
private suspend fun <T> GoogleTokenSource.withToken(missing: String, block: suspend (String) -> T): T {
    val token = accessToken() ?: throw IllegalStateException(missing)
    return try {
        block(token)
    } catch (_: GmailAuthException) {
        clearToken(token)
        block(accessToken() ?: throw IllegalStateException(missing))
    }
}

/**
 * Who an email goes to (D36). Reply: the newest inbox email from that person (or, failing that, about that topic),
 * with its thread. New: an address as said, the person's email in Contacts, or an address they emailed from before.
 */
class EmailRecipientFinder(
    /** (name, email address) pairs from Contacts. */
    private val contactEmails: suspend (String) -> List<Pair<String, String>>,
    private val readTokens: GoogleTokenSource,
    private val api: GmailApi,
) {
    suspend fun find(query: String, reply: Boolean): List<Recipient> {
        val q = EmailComposer.oneLine(query).replace("\"", "")
        val fromQuery = if ('@' in q) "from:$q" else "from:($q)"
        if (reply) {
            val refs = readTokens.withToken(SIGN_IN) { t ->
                api.replyRefs(t, "in:inbox $fromQuery", 5).ifEmpty { api.replyRefs(t, "in:inbox ($q)", 1) }
            }
            // Newest email per sender: several different senders = "Which one?".
            return refs.groupBy { EmailComposer.addressOf(it.replyTo).lowercase(Locale.ENGLISH) }.values.map { it.first() }.map { r ->
                val address = EmailComposer.addressOf(r.replyTo)
                Recipient(
                    EmailComposer.nameOf(r.replyTo), address, Channel.EMAIL, address,
                    subject = EmailComposer.replySubject(r.subject),
                    thread = EmailThread(r.threadId, r.messageId, r.references, r.subject),
                )
            }
        }
        if (EmailComposer.isAddress(q)) return listOf(Recipient(q, q, Channel.EMAIL, q))
        val fromContacts = contactEmails(q).let { found -> found.filter { it.first.equals(q, ignoreCase = true) }.ifEmpty { found } }
            .distinctBy { it.second.lowercase(Locale.ENGLISH) }
        if (fromContacts.isNotEmpty()) return fromContacts.map { (name, email) -> Recipient(name, email, Channel.EMAIL, email) }
        // Not in Contacts: someone who has emailed you.
        val past = runCatching { readTokens.withToken(SIGN_IN) { t -> api.replyRefs(t, fromQuery, 10) } }.getOrDefault(emptyList())
        return past.map { it.replyTo }.distinctBy { EmailComposer.addressOf(it).lowercase(Locale.ENGLISH) }.map {
            val address = EmailComposer.addressOf(it)
            Recipient(EmailComposer.nameOf(it), address, Channel.EMAIL, address)
        }
    }

    companion object {
        const val SIGN_IN = "Sign in to Gmail first (Settings → Gmail)."
    }
}

/** Sends through Gmail with the send-only grant (D36). */
class EmailSender(private val sendTokens: GoogleTokenSource, private val api: GmailApi) : ChannelSender {
    override suspend fun send(to: Recipient, text: String): Result<Unit> = runCatching {
        val subject = to.subject.ifBlank { EmailComposer.subjectFrom(text) }
        val raw = EmailComposer.raw(to.address, subject, text, to.thread)
        sendTokens.withToken(ALLOW) { t -> api.send(t, raw, to.thread?.threadId) }
    }

    companion object {
        const val ALLOW = "Allow JARVIS to send email first: Settings → Gmail → Allow sending."
    }
}
