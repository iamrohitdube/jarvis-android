package dev.jarvis.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.agent.AgentLoop
import dev.jarvis.data.SettingsRepository
import dev.jarvis.di.Headless
import dev.jarvis.memory.HistoryStore
import dev.jarvis.security.AndroidBiometricAvailability
import dev.jarvis.ui.wake.WakeActivity
import dev.jarvis.voice.PocketDetector
import dev.jarvis.voice.Stt
import dev.jarvis.voice.Tts
import dev.jarvis.voice.VoiceConversation
import dev.jarvis.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Always-on "Hey Jarvis" listener (CLAUDE.md section 8a): a microphone foreground service with a
 * persistent notification. On detection it frees the mic, checks the proximity sensor and either shows the
 * wake screen (phone in hand) or runs an audio-only conversation (pocket), then resumes listening.
 *
 * Android 14+ only lets a microphone service start while JARVIS is visible (or from a notification tap),
 * so it's started from the app or the "tap to resume" notification, never from boot (docs/decisions.md D21).
 */
@AndroidEntryPoint
class ListeningService : Service() {
    @Inject lateinit var keywordEngine: WakeWordEngine
    @Inject lateinit var phoneEngine: dev.jarvis.voice.SystemSpeechWakeEngine
    @Inject lateinit var pocket: PocketDetector
    @Inject lateinit var bus: WakeBus
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var router: HeadsetAudioRouter
    @Inject lateinit var stt: Stt
    @Inject lateinit var tts: Tts
    @Inject lateinit var cue: dev.jarvis.voice.WakeCue
    @Inject lateinit var history: HistoryStore
    @Inject lateinit var device: AndroidBiometricAvailability
    @Inject @Headless lateinit var headlessAgent: AgentLoop

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { settings.setWakeWordEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) loop?.cancel() // e.g. sensitivity changed
        if (loop?.isActive != true) bus.status.value = WakeBus.Status.STARTING
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)?.cancel(WakeNotifications.RESUME_ID)
        if (loop?.isActive != true) loop = scope.launch { listenLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        bus.status.value = WakeBus.Status.STOPPED
        bus.modelActive.value = false
        bus.level.value = 0f
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bus.lastError.value = "Microphone permission is off"
            bus.status.value = WakeBus.Status.ERROR
            return false
        }
        WakeNotifications.createChannels(this)
        return try {
            ServiceCompat.startForeground(
                this, WakeNotifications.LISTENING_ID, WakeNotifications.listening(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
            true
        } catch (e: Exception) {
            // Started from the background without a visible app: Android refuses mic access. Ask for a tap.
            bus.lastError.value = "Android refused to start the listener: ${e.javaClass.simpleName}"
            bus.status.value = WakeBus.Status.ERROR
            WakeNotifications.postResume(this)
            false
        }
    }

    private suspend fun engine(): WakeWordEngine =
        if (settings.current().wakeEngine == dev.jarvis.voice.WakeEngineChoice.PHONE) phoneEngine else keywordEngine

    private suspend fun listenLoop() {
        if (!engine().isReady()) {
            bus.lastError.value = "The selected wake-word engine isn't available on this phone"
            bus.status.value = WakeBus.Status.NOT_READY
            stopSelf()
            return
        }
        try {
            listenForever()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never crash the app; report it in Settings → "Hey Jarvis" and stop cleanly.
            bus.lastError.value = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            bus.status.value = WakeBus.Status.ERROR
            stopSelf()
        }
    }

    private suspend fun listenForever() {
        while (scope.isActive) {
            // Wait while the chat screen's push-to-talk owns the mic.
            if (bus.micBusy.value) { bus.status.value = WakeBus.Status.PAUSED; bus.micBusy.first { !it }; continue }
            bus.status.value = WakeBus.Status.LISTENING
            bus.lastError.value = null
            val current = settings.current()
            val engine = engine()
            val detected: dev.jarvis.voice.WakeDetection? = coroutineScope {
                val detection = async { engine.detections(current.wakeSensitivity, bus, current.wakeMicSource).first() }
                val busy = async { bus.micBusy.first { it } }
                select<dev.jarvis.voice.WakeDetection?> {
                    detection.onAwait { it }
                    busy.onAwait { null }
                }.also { coroutineContext.cancelChildren() } // stops the detector and frees the mic
            }
            if (detected != null) {
                bus.heardCount.value += 1
                bus.lastHeardAt.value = System.currentTimeMillis()
                bus.status.value = WakeBus.Status.CONVERSATION
                bus.modelActive.value = false
                handleWake(detected.followUp?.takeIf { it.isNotBlank() })
            }
        }
    }

    private suspend fun handleWake(question: String?) {
        bus.conversationActive.value = true
        bus.wakeScreenShown.value = false
        try {
            if (!pocket.inPocket() && showWakeScreen(question)) {
                // Give the screen a moment to appear; if it never does (alert ignored), carry on listening.
                val shown = withTimeoutOrNull(SHOW_TIMEOUT_MS) { bus.wakeScreenShown.first { it } } != null
                if (shown) withTimeoutOrNull(CONVERSATION_TIMEOUT_MS) { bus.conversationActive.first { !it } }
                WakeNotifications.cancelWakeAlert(this)
            } else {
                runHeadless(question)
            }
        } finally {
            bus.conversationActive.value = false
        }
    }

    /** Opens the wake screen over everything: directly if "Display over other apps" is allowed, else via a full-screen alert. */
    private fun showWakeScreen(question: String?): Boolean {
        val target = WakeActivity.intent(this, question)
        // As default assistant (system-bound) or with "Display over other apps", JARVIS may open screens from the background.
        if (JarvisVoiceInteractionService.isDefaultAssistant(this) || Settings.canDrawOverlays(this)) {
            return runCatching { startActivity(target); true }.getOrDefault(false)
        }
        WakeNotifications.postWakeAlert(this, target)
        return true
    }

    /** Pocket mode: no screen. Talk through a Bluetooth headset if one is connected. */
    private suspend fun runHeadless(question: String?) {
        val routed = router.routeToHeadset()
        tts.setCallRoute(routed)
        try {
            VoiceConversation(stt, tts, headlessAgent, history, settings, cue) { device.deviceLocked() }.runOnce(scope, question)
        } finally {
            tts.setCallRoute(false)
            router.reset()
        }
    }

    companion object {
        const val ACTION_START = "dev.jarvis.action.START_LISTENING"
        const val ACTION_STOP = "dev.jarvis.action.STOP_LISTENING"
        const val ACTION_RESTART = "dev.jarvis.action.RESTART_LISTENING"
        private const val SHOW_TIMEOUT_MS = 8_000L
        private const val CONVERSATION_TIMEOUT_MS = 180_000L

        /** Call only while JARVIS is visible (Android 14+ rule for microphone services). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).setAction(ACTION_START))
        }

        /** Restart with new settings (only while JARVIS is visible). */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java).setAction(ACTION_RESTART))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_STOP))
        }
    }
}
