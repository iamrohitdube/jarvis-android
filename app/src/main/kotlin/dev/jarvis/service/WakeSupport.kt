package dev.jarvis.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordination between the wake-word service, the wake screen and the chat screen.
 * - [conversationActive]: a "Hey Jarvis" exchange is running; the detector stays paused.
 * - [wakeScreenShown]: the wake screen actually appeared (vs. a full-screen alert the user ignored).
 * - [micBusy]: the chat screen's push-to-talk is using the mic; the detector pauses so they don't fight.
 */
@Singleton
class WakeBus @Inject constructor() : dev.jarvis.voice.WakeDiagnostics, dev.jarvis.voice.WakeTranscriptSink {
    /** Last thing the phone-recognition listener heard (memory only, shown in Settings for troubleshooting). */
    val lastHeard = MutableStateFlow<String?>(null)
    override fun onHeard(text: String) { lastHeard.value = text.take(80) }

    val conversationActive = MutableStateFlow(false)
    val wakeScreenShown = MutableStateFlow(false)
    val micBusy = MutableStateFlow(false)

    // ---- diagnostics for Settings ----
    enum class Status { STOPPED, STARTING, LISTENING, PAUSED, CONVERSATION, NOT_READY, ERROR }
    val status = MutableStateFlow(Status.STOPPED)
    val level = MutableStateFlow(0f)
    val modelActive = MutableStateFlow(false)
    val heardCount = MutableStateFlow(0)
    val lastHeardAt = MutableStateFlow<Long?>(null)
    /** Short technical reason (no audio, no transcripts). */
    val lastError = MutableStateFlow<String?>(null)

    val gain = MutableStateFlow(1f)

    override fun onAudio(level: Float, modelActive: Boolean, gain: Float) {
        this.level.value = level
        this.modelActive.value = modelActive
        this.gain.value = gain
    }
}

/**
 * Routes a hands-free (pocket) conversation through a connected Bluetooth headset, using the current API
 * (setCommunicationDevice, Android 12+; works for classic HFP and LE Audio headsets). Returns false when
 * there's no headset, in which case the phone's own mic and speaker are used.
 */
class HeadsetAudioRouter(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun routeToHeadset(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audio == null) return false
        val headset = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } ?: return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return runCatching { audio.setCommunicationDevice(headset) }.getOrDefault(false)
    }

    fun reset() {
        if (audio == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audio.clearCommunicationDevice() }
        audio.mode = AudioManager.MODE_NORMAL
    }
}

/** Notification channels and builders for the wake-word feature. */
object WakeNotifications {
    const val LISTENING_ID = 4001
    const val WAKE_ID = 4002
    const val RESUME_ID = 4003
    private const val CH_LISTENING = "listening"
    private const val CH_WAKE = "wake"
    private const val CH_RESUME = "resume"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_LISTENING, "Listening for \"Hey Jarvis\"", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while JARVIS listens for its wake word. Audio stays on the phone."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WAKE, "\"Hey Jarvis\" pop-up", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Opens JARVIS when you say \"Hey Jarvis\" (fallback when the overlay isn't allowed)."
        })
        nm.createNotificationChannel(NotificationChannel(CH_RESUME, "Resume listening", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** The persistent notification Android requires while the microphone service runs. */
    fun listening(context: Context): android.app.Notification {
        val open = PendingIntent.getActivity(
            context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, ListeningService::class.java).setAction(ListeningService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CH_LISTENING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"Hey Jarvis\"")
            .setContentText("On-device. Nothing is recorded or sent until you say it.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Full-screen alert that opens the wake screen (used when "Display over other apps" isn't granted). */
    fun postWakeAlert(context: Context, target: Intent) {
        val pi = PendingIntent.getActivity(context, 2, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CH_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS")
            .setContentText("Listening…")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(WAKE_ID, n) }
    }

    fun cancelWakeAlert(context: Context) = NotificationManagerCompat.from(context).cancel(WAKE_ID)

    /**
     * After a reboot (or if the service couldn't start from the background), Android won't let the mic
     * service start on its own; a tap on this notification is allowed to start it.
     */
    fun postResume(context: Context) {
        val start = PendingIntent.getForegroundService(
            context, 3, Intent(context, ListeningService::class.java).setAction(ListeningService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_RESUME)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Tap to resume \"Hey Jarvis\"")
            .setContentText("Android needs a tap before JARVIS can listen again.")
            .setContentIntent(start)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(RESUME_ID, n) }
    }
}
