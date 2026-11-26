package dev.jarvis.messaging

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.connectors.google.EmailFilter
import dev.jarvis.connectors.google.EmailHeader
import dev.jarvis.data.db.CapturedMessageDao
import dev.jarvis.data.db.CapturedMessageEntity
import dev.jarvis.data.db.CapturedNotificationDao
import dev.jarvis.data.db.CapturedNotificationEntity
import dev.jarvis.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** A chat message pulled out of a notification. */
data class ChatNotice(val app: String, val packageName: String, val sender: String, val conversation: String, val text: String, val isGroup: Boolean)

/**
 * Live reply handles from chat notifications (the notification's own reply button). A reply works only while the
 * notification is showing (CLAUDE.md section 7: WhatsApp is sent only via the notification reply action).
 */
object ReplyRegistry {
    private val actions = ConcurrentHashMap<String, Notification.Action>()

    fun put(key: String, action: Notification.Action) { actions[key] = action }
    fun remove(key: String) { actions.remove(key) }
    fun has(key: String) = actions.containsKey(key)

    /** Fills the reply box of the notification [key] with [text] and presses its send action. */
    fun reply(context: Context, key: String, text: String): Result<Unit> = runCatching {
        val action = actions[key] ?: throw IllegalStateException("That chat notification is gone; open the app to reply")
        val inputs = action.remoteInputs ?: throw IllegalStateException("That notification can't be replied to")
        val intent = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(context, 0, intent)
    }
}

/** Pure helpers: which notifications are chat messages, and de-duplication (apps re-post whole threads). */
object ChatNotices {
    /** Apps whose message notifications are captured. Others are ignored. */
    val APPS = mapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal", "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Messages", "com.android.mms" to "Messages", "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram", "com.google.android.apps.dynamite" to "Google Chat", "com.slack" to "Slack",
        "com.microsoft.teams" to "Teams",
    )

    /** One-time codes are never kept (same rule as email). */
    fun isCode(text: String) = EmailFilter.isOneTimeCode(EmailHeader("", "", 0, false, snippet = text))

    private val recent = ArrayDeque<Int>()

    /** True the first time a (app, conversation, sender, text) is seen among the last 500. */
    @Synchronized fun firstTime(n: ChatNotice): Boolean {
        val h = listOf(n.packageName, n.conversation, n.sender, n.text).hashCode()
        if (h in recent) return false
        recent.addLast(h)
        while (recent.size > 500) recent.removeFirst()
        return true
    }
}

/**
 * Reads chat-message notifications (D29 step 8) into the local database so JARVIS can read them back and reply.
 * Only known chat apps; one-time codes skipped; content is untrusted DATA (never instructions). Enabled by the user
 * in Android's notification access settings. Other apps' notifications are kept too, but only while the user's
 * "Other app notifications" switch is on (D35, [AppNotices]).
 */
@AndroidEntryPoint
class JarvisNotificationListener : NotificationListenerService() {
    @Inject lateinit var dao: CapturedMessageDao
    @Inject lateinit var notices: CapturedNotificationDao
    @Inject lateinit var settings: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val app = ChatNotices.APPS[sbn.packageName] ?: return captureOther(sbn)
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val notice = extract(app, sbn.packageName, n) ?: return
        if (notice.text.isBlank() || ChatNotices.isCode(notice.text) || !ChatNotices.firstTime(notice)) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true && a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        reply?.let { ReplyRegistry.put(sbn.key, it) }
        scope.launch {
            dao.insert(
                CapturedMessageEntity(
                    app = notice.app, packageName = notice.packageName, sender = notice.sender.take(80),
                    conversation = notice.conversation.take(80), text = notice.text.take(1000), at = sbn.postTime,
                    isGroup = notice.isGroup, replyKey = if (reply != null) sbn.key else null,
                )
            )
            dao.deleteOlderThan(System.currentTimeMillis() - KEEP_MS)
        }
    }

    /** Other apps (D35): kept only while the user's "Other app notifications" switch is on. */
    private fun captureOther(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val notice = AppNotice(
            app = appName(sbn.packageName), packageName = sbn.packageName, title = title.take(120), text = text.take(500),
            category = n.category,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            secret = n.visibility == Notification.VISIBILITY_SECRET,
        )
        scope.launch {
            val s = settings.current()
            if (!s.readAppNotifications || s.privateMode) return@launch
            if (!AppNotices.keep(notice, packageName, AppNotices.ignored(s.ignoredNotificationApps)) || !AppNotices.firstTime(notice)) return@launch
            notices.insert(
                CapturedNotificationEntity(
                    app = notice.app, packageName = notice.packageName, title = notice.title, text = notice.text,
                    category = notice.category, at = sbn.postTime,
                )
            )
            notices.deleteOlderThan(System.currentTimeMillis() - AppNotices.KEEP_MS)
        }
    }

    private val labels = ConcurrentHashMap<String, String>()

    /** The app's name as the launcher shows it (needs the launcher <queries> entry on Android 11+). */
    private fun appName(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: AppNotices.nameFromPackage(pkg)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        ReplyRegistry.remove(sbn.key)
        scope.launch { dao.clearReplyKey(sbn.key) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun extract(app: String, pkg: String, n: Notification): ChatNotice? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null) {
            val last = style.messages.lastOrNull() ?: return null
            val sender = last.person?.name?.toString() ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val conversation = style.conversationTitle?.toString() ?: sender
            return ChatNotice(app, pkg, sender, conversation, last.text?.toString().orEmpty(), style.isGroupConversation)
        }
        if (n.category != Notification.CATEGORY_MESSAGE) return null
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return ChatNotice(app, pkg, title, title, text, isGroup = false)
    }

    companion object {
        /** Captured chat messages are kept 3 days. */
        const val KEEP_MS = 3 * 24 * 3600_000L

        fun component(context: Context) = ComponentName(context, JarvisNotificationListener::class.java)

        fun enabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Android's notification access screen for JARVIS (falls back to the list on older versions). */
        fun settingsIntent(context: Context): Intent =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            } else Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
