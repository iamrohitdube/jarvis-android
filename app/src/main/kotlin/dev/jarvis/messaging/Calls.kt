package dev.jarvis.messaging

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditSink
import dev.jarvis.data.db.SentMessageDao
import dev.jarvis.data.db.SentMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Who to call: a contact's name and number. [detail] is what is said and shown ("number ending 2222"). */
data class CallTarget(val name: String, val number: String, val detail: String)

/**
 * "Call Mom", "dial 98765 43210", "ring Ravi please": the phone handles these itself, with no Claude request (D36).
 * Returns who to call, or null when the text isn't a call request ("call log", "who called", "call me back").
 */
object CallIntent {
    private val PATTERN = Regex(
        "^(?:(?:hey|ok|okay)\\s+)?(?:jarvis[,]?\\s+)?(?:please\\s+|can you\\s+|could you\\s+|would you\\s+)?" +
            "(?:call|dial|ring|phone|make a call to|place a call to)\\s+(.+?)" +
            "(?:\\s+(?:please|now|for me|right now|on (?:his|her|their) mobile))*[.!?]*$",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_A_PERSON = setOf(
        "log", "logs", "history", "list", "recording", "recordings", "me", "me back", "back", "it", "that", "this",
        "him", "her", "them", "someone", "somebody",
    )
    /** "call it a day", "call me later", "call log entries", "call history for Mom". */
    private val NOT_A_FIRST_WORD = setOf("it", "that", "this", "me", "back", "log", "logs", "history")

    fun target(text: String): String? {
        val m = PATTERN.find(text.trim()) ?: return null
        val who = m.groupValues[1].trim().removePrefix("to ").removePrefix("my ").trim()
        val lower = who.lowercase(Locale.ENGLISH)
        val first = lower.substringBefore(' ')
        if (who.isEmpty() || lower in NOT_A_PERSON || first in NOT_A_FIRST_WORD) return null
        return who.take(60)
    }
}

/** The word that places the call: "call" (or "dial", "go ahead"). A bare "yes" is not enough; any negation stops it. */
object CallPhrase {
    private val NEGATION = Regex("\\b(don'?t|do not|never|not|no|stop|cancel|wait|hold)\\b")

    fun approves(heard: String): Boolean {
        val t = heard.lowercase(Locale.ENGLISH).replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return Regex("\\b(call|dial|go ahead|ring)\\b").containsMatchIn(t) && !NEGATION.containsMatchIn(t)
    }

    fun cancels(heard: String): Boolean =
        Regex("\\b(cancel|stop|don'?t|do not|no)\\b").containsMatchIn(heard.lowercase(Locale.ENGLISH))
}

/** Finds who to call (contacts). Throws SecurityException without the contacts permission. */
fun interface CallFinder {
    suspend fun find(query: String): List<CallTarget>
}

/** Starts the call. Returns what to say ("Calling Mom.") or a failure. */
fun interface CallPlacer {
    suspend fun place(target: CallTarget): Result<String>
}

/**
 * Voice calling (D36): resolve the contact (ask which if several match), say who and which number, wait for "call"
 * (or a tap), then place the call. Every call is logged (`sent_messages`, channel "call") and audited.
 */
class Caller(
    private val finder: CallFinder,
    private val placer: CallPlacer,
    /** Shows/says "Call Mom, number ending 2222?" and waits. True only on an explicit "call" or a tap. */
    private val confirm: suspend (CallTarget) -> Boolean,
    private val log: SentMessageDao,
    private val audit: AuditSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun handle(to: String?): String = mutex.withLock {
        val who = to?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: return@withLock "Who should I call?"
        val digits = who.filter { it.isDigit() || it == '+' }
        val matches = if (digits.count { it.isDigit() } >= 6 && who.none { it.isLetter() }) {
            listOf(CallTarget(digits, digits, "number ending ${digits.takeLast(4)}"))
        } else try {
            finder.find(who)
        } catch (e: SecurityException) {
            return@withLock "I need access to your contacts to call people. Allow it in Settings → Messaging → Phone calls."
        }
        when {
            matches.isEmpty() -> return@withLock "I can't find \"$who\" in your contacts."
            matches.size > 1 -> return@withLock "Which one? " +
                matches.take(4).joinToString(", ") { "${it.name} (${it.detail})" } + ". Say the full name."
        }
        val target = matches.single()
        audit.record(AuditEvent(AuditCategory.TOOL, "call", "asked", tool = TOOL))
        if (!confirm(target)) {
            audit.record(AuditEvent(AuditCategory.TOOL, "call", "not_confirmed", tool = TOOL))
            return@withLock "Not calling."
        }
        val approvedAt = clock()
        val result = try { placer.place(target) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        log.insert(
            SentMessageEntity(
                channel = "call", recipient = target.name, text = "", approvedAt = approvedAt,
                sentAt = if (result.isSuccess) clock() else null, outcome = if (result.isSuccess) "called" else "failed",
            )
        )
        audit.record(AuditEvent(AuditCategory.TOOL, "call", if (result.isSuccess) "ok" else "error", tool = TOOL))
        result.getOrElse { "Couldn't call ${target.name}: ${it.message ?: "unknown error"}." }
    }

    companion object {
        const val TOOL = "make_call"
    }
}

/** Contacts lookup for calls: one entry per number, labelled by its last 4 digits. */
class ContactCallFinder(private val contacts: Contacts) : CallFinder {
    override suspend fun find(query: String): List<CallTarget> = withContext(Dispatchers.IO) {
        val all = contacts.find(query).map { (name, number) -> CallTarget(name, number, "number ending ${number.filter { it.isDigit() }.takeLast(4)}") }
        // An exact name match wins over partial ones ("Ravi" vs "Ravi Kumar" and "Ravindra").
        all.filter { it.name.equals(query, ignoreCase = true) }.ifEmpty { all }
    }
}

/**
 * Places the call through Android's telecom service (needs CALL_PHONE; works from the background service and the
 * lock screen because the system dialer shows the call). Without the permission, opens the dialer with the number.
 */
class AndroidCallPlacer(private val context: Context) : CallPlacer {
    override suspend fun place(target: CallTarget): Result<String> = runCatching {
        val uri = Uri.fromParts("tel", target.number, null)
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            context.getSystemService(TelecomManager::class.java).placeCall(uri, null)
            "Calling ${target.name}."
        } else {
            context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "I've opened the dialer with ${target.name}'s number: tap call. To call directly, allow phone calls in Settings → Messaging."
        }
    }

    companion object {
        val PERMISSIONS = listOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_CONTACTS)
    }
}
