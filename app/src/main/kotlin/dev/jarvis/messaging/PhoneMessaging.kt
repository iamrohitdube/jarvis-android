package dev.jarvis.messaging

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.connectors.phone.LocalConnector
import dev.jarvis.data.db.CapturedMessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One message as the phone keeps it (SMS inbox or a captured chat notification). Untrusted DATA. */
@Serializable
data class PhoneMessage(val from: String, val app: String, val at: String, val text: String, val conversation: String = "")

@Serializable
data class MessagesResult(val source: String, val query: String? = null, val messages: List<PhoneMessage> = emptyList())

private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private fun local(ms: Long, zone: ZoneId) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(LOCAL)

/** Contact names and numbers (READ_CONTACTS). */
class Contacts(private val resolver: ContentResolver) {
    /** Display name for a phone number, or null. */
    fun nameOf(number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Contacts whose name contains every word of [query], with their mobile numbers (one row per distinct number). */
    fun find(query: String): List<Pair<String, String>> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val out = LinkedHashMap<String, Pair<String, String>>()
        resolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0).orEmpty()
                val number = c.getString(1).orEmpty()
                val key = number.filter { it.isDigit() }.takeLast(10)
                if (key.length >= 6 && key !in out) out[key] = name to number
            }
        }
        return out.values.toList()
    }
}

/** SMS inbox (READ_SMS) + sending (SEND_SMS) + contact names (READ_CONTACTS). Restricted for file installs. */
class SmsConnector(
    private val resolver: ContentResolver,
    private val contacts: Contacts,
    private val json: Json,
    granted: () -> Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : LocalConnector(
    ToolRegistry.SMS, "SMS", granted, PERMISSIONS,
    "Read your texts and send SMS after you confirm. One-time codes are skipped.",
) {
    override suspend fun read(tool: String, arguments: JsonObject): String {
        val query = if (tool == FROM) arguments.text("query") ?: throw IllegalArgumentException("Nobody to look for") else null
        val names = HashMap<String, String?>()
        val list = mutableListOf<PhoneMessage>()
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.Inbox.DEFAULT_SORT_ORDER,
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < 500 && list.size < (if (query == null) 15 else 10)) {
                val address = c.getString(0).orEmpty()
                val body = c.getString(1).orEmpty()
                if (ChatNotices.isCode(body)) continue
                val name = names.getOrPut(address) { contacts.nameOf(address) } ?: address
                if (query != null && !MessageMatch.matches(name, address, query)) continue
                list += PhoneMessage(name.take(60), "SMS", local(c.getLong(2), zone()), body.take(500))
            }
        }
        return json.encodeToString(MessagesResult.serializer(), MessagesResult("sms", query, list))
    }

    companion object {
        const val RECENT = "sms_recent"
        const val FROM = "sms_from"
        val PERMISSIONS = listOf(
            android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_CONTACTS,
        )
    }
}

/** Chat messages captured from notifications (WhatsApp, Telegram…). Needs notification access. */
class ChatsConnector(
    private val dao: CapturedMessageDao,
    private val json: Json,
    granted: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : LocalConnector(
    ToolRegistry.CHATS, "Chat messages", granted, emptyList(),
    "WhatsApp, Telegram and other chat messages from their notifications; reply while the notification is showing.",
) {
    override suspend fun read(tool: String, arguments: JsonObject): String {
        val query = if (tool == FROM) arguments.text("query") ?: throw IllegalArgumentException("Nobody to look for") else null
        val since = clock() - if (query == null) DAY else 3 * DAY
        val rows = dao.since(since, 300).filter { query == null || MessageMatch.matches(it.conversation + " " + it.sender, "", query) }
        val messages = rows.take(if (query == null) 20 else 12).map {
            PhoneMessage(it.sender, it.app, local(it.at, zone()), it.text.take(500), it.conversation.takeIf { c -> c != it.sender }.orEmpty())
        }
        return json.encodeToString(MessagesResult.serializer(), MessagesResult("chats", query, messages))
    }

    companion object {
        const val RECENT = "chats_recent"
        const val FROM = "chats_from"
        private const val DAY = 24 * 3600_000L
    }
}

object MessageMatch {
    /** Every word of [query] in the name, or its digits in the number. */
    fun matches(name: String, number: String, query: String): Boolean {
        val digits = query.filter { it.isDigit() }
        if (digits.length >= 4 && number.filter { it.isDigit() }.contains(digits.takeLast(10))) return true
        val words = query.lowercase(Locale.ENGLISH).split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { name.lowercase(Locale.ENGLISH).contains(it) }
    }
}

/** Contacts for SMS; live chat notifications for replies. */
class AndroidRecipientFinder(private val contacts: Contacts, private val dao: CapturedMessageDao, private val clock: () -> Long = System::currentTimeMillis) : RecipientFinder {
    override suspend fun find(channel: Channel, query: String, app: String?): List<Recipient> = withContext(Dispatchers.IO) {
        when (channel) {
            Channel.SMS -> {
                val digits = query.filter { it.isDigit() || it == '+' }
                if (digits.count { it.isDigit() } >= 7 && query.none { it.isLetter() }) {
                    listOf(Recipient(contacts.nameOf(digits) ?: digits, digits, Channel.SMS, "number ending ${digits.takeLast(4)}"))
                } else {
                    val found = contacts.find(query)
                    // An exact name wins over partial matches ("Mom" vs "Mom Office").
                    val exact = found.filter { it.first.equals(query.trim(), ignoreCase = true) }
                    (exact.ifEmpty { found }).map { (name, number) ->
                        Recipient(name, number, Channel.SMS, "number ending ${number.filter { it.isDigit() }.takeLast(4)}")
                    }
                }
            }
            Channel.CHAT -> {
                val live = dao.since(clock() - 3 * 24 * 3600_000L, 500)
                    .filter { it.replyKey != null && ReplyRegistry.has(it.replyKey) }
                    .filter { app == null || it.app.lowercase(Locale.ENGLISH).contains(app) || it.packageName.contains(app) }
                    .filter { MessageMatch.matches(it.conversation + " " + it.sender, "", query) }
                // Latest notification per conversation.
                live.groupBy { it.packageName + "/" + it.conversation }.values.map { it.maxBy { m -> m.at } }.map {
                    Recipient(it.conversation, it.replyKey!!, Channel.CHAT, it.app, it.packageName)
                }
            }
        }
    }
}

/** SMS through Android's SmsManager (the system keeps a copy in Sent). */
class SmsSender(private val context: Context) : ChannelSender {
    override suspend fun send(to: Recipient, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sms = context.getSystemService(SmsManager::class.java) ?: throw IllegalStateException("This phone can't send SMS")
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(to.address, null, parts, null, null)
            else sms.sendTextMessage(to.address, null, text, null, null)
        }
    }
}

/** A reply through the chat app's own notification reply action. */
class ReplySender(private val context: Context) : ChannelSender {
    override suspend fun send(to: Recipient, text: String): Result<Unit> = ReplyRegistry.reply(context, to.address, text)
}
