package dev.jarvis.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.jarvis.R
import dev.jarvis.agent.MeetingPrep
import dev.jarvis.agent.PeopleAnswers
import dev.jarvis.agent.PeopleConnector
import dev.jarvis.data.AuditCategory
import dev.jarvis.data.AuditEvent
import dev.jarvis.data.AuditLog
import dev.jarvis.data.SettingsRepository
import dev.jarvis.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Set by a tapped meeting card; the People screen opens that person's brief (Phase 15.2). */
object PeopleLaunch {
    val pending = MutableStateFlow<String?>(null)
    const val EXTRA_PERSON = "dev.jarvis.extra.PERSON"
}

/**
 * Meeting prep cards (Phase 15.2, D38): every ~15 minutes, meetings starting within 35 minutes get one notification
 * with a brief on the people named in the title. Built on the phone; nothing goes to Claude. The lock screen shows
 * only "Meeting soon". Off in Settings → Phone data, or with Private mode.
 */
class MeetingPrepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun people(): PeopleConnector
        fun settings(): SettingsRepository
        fun audit(): AuditLog
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val s = deps.settings().current()
        if (!s.meetingPrepCards || s.privateMode || !canNotify(applicationContext)) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(SENT, emptySet()).orEmpty()
        val now = LocalDateTime.now()
        val preps = deps.people().nextMeetings(now, MeetingPrep.LEAD_MINUTES) { MeetingPrep.due(it, now, sent) }
        if (preps.isEmpty()) return Result.success()
        ensureChannel(applicationContext)
        val zoned = ZonedDateTime.now()
        for (p in preps) {
            val e = p.event ?: continue
            val title = "${MeetingPrep.startsIn(e, now).replaceFirstChar { it.uppercase() }}: ${e.title}"
            val body = (if (e.location.isNotBlank()) "At ${e.location}\n" else "") +
                if (p.people.isEmpty()) "No one named in the title." else p.people.joinToString("\n") { PeopleAnswers.section(it, zoned, skipMeeting = true) }
            notify(applicationContext, MeetingPrep.key(e), title, body, p.people.firstOrNull()?.name)
            deps.audit().record(AuditEvent(AuditCategory.DATA, "meeting_prep_card", "shown", tool = PeopleConnector.MEETING))
        }
        // Remember what was sent (hashed keys only), keeping the latest 200.
        val updated = (sent.toList() + preps.mapNotNull { it.event?.let(MeetingPrep::key) }).takeLast(200).toSet()
        prefs.edit().putStringSet(SENT, updated).apply()
        return Result.success()
    }

    companion object {
        private const val NAME = "meeting-prep"
        private const val CHANNEL = "meeting_prep"
        private const val PREFS = "meeting_prep"
        private const val SENT = "sent"

        /** Runs every 15 minutes (WorkManager's minimum); each run checks the switch itself. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MeetingPrepWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun canNotify(c: Context) = NotificationManagerCompat.from(c).areNotificationsEnabled()

        private fun ensureChannel(c: Context) {
            c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Meeting prep", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A brief on the people in a meeting, shortly before it starts"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }

        private fun notify(c: Context, key: String, title: String, body: String, person: String?) {
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { person?.let { putExtra(PeopleLaunch.EXTRA_PERSON, it) } }
            val tap = PendingIntent.getActivity(c, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // What the lock screen shows: no title, no names.
            val public = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Meeting soon").setContentText("Unlock to see the prep").build()
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(c).notify(key.hashCode(), n) } // SecurityException if revoked meanwhile
        }
    }
}
