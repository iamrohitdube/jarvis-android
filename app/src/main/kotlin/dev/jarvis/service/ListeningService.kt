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
    @Inject lateinit var heyJarvisEngine: dev.jarvis.voice.OpenWakeWordEngine
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
    @Inject lateinit var voicePin: dev.jarvis.voice.VoicePinEngine
    @Inject lateinit var voiceCapture: dev.jarvis.voice.VoiceCapture
    @Inject lateinit var transcriber: dev.jarvis.voice.Transcriber
    @Inject lateinit var auth: dev.jarvis.security.AuthCoordinator

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

    private suspend fun engine(): WakeWordEngine = when (settings.current().wakeEngine) {
        dev.jarvis.voice.WakeEngineChoice.HEY_JARVIS -> heyJarvisEngine
        dev.jarvis.voice.WakeEngineChoice.PHONE -> phoneEngine
        dev.jarvis.voice.WakeEngineChoice.KEYWORD_MODEL -> keywordEngine
    }

    private suspend fun listenLoop() {
        if (!engine().isReady()) {
            bus.lastError.value = if (settings.current().wakeEngine == dev.jarvis.voice.WakeEngineChoice.HEY_JARVIS)
                "Download the wake model first (Settings → Hey Jarvis)" else "The selected wake-word engine isn't available on this phone"
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
            dev.jarvis.voice.dlog("listener: ERROR ${e.javaClass.simpleName}: ${e.message} at ${e.stackTrace.take(4).joinToString(" < ")}")
            bus.status.value = WakeBus.Status.ERROR
            stopSelf()
        }
    }

    private suspend fun listenForever() {
        while (scope.isActive) {
            // Wait while the chat screen's push-to-talk owns the mic.
            if (bus.micBusy.value) { bus.status.value = WakeBus.Status.PAUSED; dev.jarvis.voice.dlog("listener: PAUSED (mic busy)"); bus.micBusy.first { !it }; continue }
            bus.status.value = WakeBus.Status.LISTENING
            dev.jarvis.voice.dlog("listener: LISTENING")
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
            dev.jarvis.voice.dlog("listener: detector ended, detected=${detected != null}")
            if (detected != null) {
                bus.heardCount.value += 1
                bus.lastWakeFeatures.value = detected.features
                bus.lastHeardAt.value = System.currentTimeMillis()
                bus.status.value = WakeBus.Status.CONVERSATION
                bus.modelActive.value = false
                // React at once; the question (said in the same breath) is recorded and transcribed meanwhile.
                val question = scope.async {
                    detected.followUp?.takeIf { it.isNotBlank() }
                        ?: detected.followUpAudio?.await()?.let { transcribeFollowUp(it) }
                }
                handleWake(question, instant = detected.followUpAudio != null)
            }
        }
    }

    /** The question said in the same breath as "Hey Jarvis": phone recognizer first, then on-device Moonshine. */
    private suspend fun transcribeFollowUp(pcm: ShortArray): String? {
        val text = transcriber.transcribe(pcm, settings.current().preferOnDeviceStt)
        val q = dev.jarvis.voice.FollowUp.clean(text)
        dev.jarvis.voice.dlog("wake: follow-up transcribed=${text != null}, usable=${q != null}")
        return q
    }

    private suspend fun handleWake(pendingQuestion: kotlinx.coroutines.Deferred<String?>, instant: Boolean) {
        bus.conversationActive.value = true
        bus.wakeScreenShown.value = false
        try {
            val inPocket = pocket.inPocket()
            if (inPocket) { val routed = router.routeToHeadset(); tts.setCallRoute(routed) }
            // In a pocket there's no screen, so the sound plays at once. In hand, the screen shows the wake at once and
            // the sound waits until the question has been recorded (so it isn't recorded with it).
            val soundPlayed = instant && inPocket && settings.current().wakeAck != dev.jarvis.voice.WakeAck.OFF
            bus.wakeSoundPlayed.value = soundPlayed
            if (soundPlayed) scope.launch { playWakeSound() }
            // Keyword engines: the screen opens now and waits for the question; phone recognition already has it.
            val question: String? = if (instant) null else pendingQuestion.await()
            if (instant) bus.pendingQuestion.value = pendingQuestion
            if (!inPocket && showWakeScreen(question)) {
                // Give the screen a moment to appear; if it never does (alert ignored), carry on listening.
                val shown = withTimeoutOrNull(SHOW_TIMEOUT_MS) { bus.wakeScreenShown.first { it } } != null
                if (shown) withTimeoutOrNull(CONVERSATION_TIMEOUT_MS) { bus.conversationActive.first { !it } }
                WakeNotifications.cancelWakeAlert(this)
            } else {
                runHeadless(if (instant) pendingQuestion.await() else question, soundPlayed)
            }
        } finally {
            bus.pendingQuestion.value = null
            bus.conversationActive.value = false
            tts.setCallRoute(false)
            router.reset()
        }
    }

    /** The wake sound only (a spoken phrase, if chosen, comes once JARVIS knows whether a question followed). */
    private suspend fun playWakeSound() {
        val s = settings.current()
        val plan = dev.jarvis.voice.AckPlan.of(s.wakeAck, s.wakeAckPhrase, preheard = true, speakReplies = s.speakReplies)
        runCatching { cue.play(plan.copy(phrase = null), tts) }
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
    private suspend fun runHeadless(question: String?, soundPlayed: Boolean) {
        // No screen: a PIN prompt is answered by voice ("Say your PIN.").
        val pinFlow = dev.jarvis.voice.VoicePinFlow(voicePin, tts, auth, settings, bus.micBusy)
        val pinWatcher = scope.launch { auth.prompt.collect { p -> pinFlow.onPrompt(p, voiceTurn = true) } }
        try {
            VoiceConversation(stt, tts, headlessAgent, history, settings, cue, voiceCapture, transcriber) { device.deviceLocked() }
                .runOnce(scope, question, soundPlayed)
        } finally {
            pinWatcher.cancel()
        }
    }

    companion object {
        const val ACTION_START = "dev.jarvis.action.START_LISTENING"
        const val ACTION_STOP = "dev.jarvis.action.STOP_LISTENING"
        const val ACTION_RESTART = "dev.jarvis.action.RESTART_LISTENING"
        private const val SHOW_TIMEOUT_MS = 8_000L
        private const val CONVERSATION_TIMEOUT_MS = 600_000L

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
