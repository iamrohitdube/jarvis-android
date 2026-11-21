package dev.jarvis.ui.wake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.agent.AgentLoop
import dev.jarvis.data.SettingsRepository
import dev.jarvis.memory.HistoryStore
import dev.jarvis.security.AndroidBiometricAvailability
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.AuthPrompt
import dev.jarvis.security.BiometricGate
import dev.jarvis.service.WakeBus
import dev.jarvis.ui.security.AuthHost
import dev.jarvis.ui.theme.JarvisTheme
import dev.jarvis.voice.SpokenChoice
import dev.jarvis.voice.Stt
import dev.jarvis.voice.SttEvent
import dev.jarvis.voice.Tts
import dev.jarvis.voice.VoiceConversation
import dev.jarvis.voice.VoiceSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class WakeViewModel @Inject constructor(
    private val stt: Stt,
    private val tts: Tts,
    agent: AgentLoop,
    history: HistoryStore,
    private val settings: SettingsRepository,
    private val auth: AuthCoordinator,
    device: AndroidBiometricAvailability,
    private val recorder: dev.jarvis.voice.VoicePinEngine,
    cue: dev.jarvis.voice.WakeCue,
    private val bus: WakeBus,
    private val heyJarvis: dev.jarvis.voice.OpenWakeWordEngine,
    private val capture: dev.jarvis.voice.VoiceCapture,
    private val transcriber: dev.jarvis.voice.Transcriber,
    savedState: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    /** Question said together with the wake word, if any (passed by the listener). */
    private val preheard: String? = savedState[WakeActivity.EXTRA_QUESTION]
    private val voicePin = dev.jarvis.voice.VoicePinFlow(recorder, tts, auth, settings)
    private val conversation = VoiceConversation(stt, tts, agent, history, settings, cue, capture, transcriber) { device.deviceLocked() }
    val phase = conversation.phase
    val transcript = conversation.transcript
    val reply = conversation.reply
    val level = conversation.level

    init {
        viewModelScope.launch {
            // Opened the instant "Hey Jarvis" was heard: wait for the question said in the same breath (if any).
            val pending = bus.pendingQuestion.value
            val question = preheard ?: pending?.await()
            val soundPlayed = pending != null && bus.wakeSoundPlayed.value
            conversation.runOnce(viewModelScope, question, soundPlayed)
        }
        viewModelScope.launch { auth.prompt.collect { p -> voicePin.onPrompt(p, voiceTurn = true) } }
        // Phone in hand: ask "PIN or fingerprint?" out loud and accept a spoken answer.
        viewModelScope.launch {
            var asked = -1L
            auth.prompt.collect { p ->
                if (p?.stage == AuthPrompt.Stage.CHOOSE && p.id != asked && settings.current().speakReplies) {
                    asked = p.id
                    // Two tries within the answer window; unclear twice = the coordinator falls back to fingerprint.
                    var choice: SpokenChoice.Choice? = null
                    for (attempt in 0..1) {
                        tts.speak(if (attempt == 0) VoiceSession.CHOICE_QUESTION else "Say PIN or fingerprint.")
                        withTimeoutOrNull(4_000) { tts.speaking.first { it }; tts.speaking.first { !it } }
                        // Record a fixed 3.5 s on our own mic (no speech detection: a quiet "PIN" must not be missed),
                        // then transcribe once.
                        val audio = recorder.recordFor(3_500)
                        val pcm = ShortArray(audio.size) { (audio[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                        val text = transcriber.transcribe(pcm, settings.current().preferOnDeviceStt)
                        choice = text?.let { SpokenChoice.parse(it) }
                        dev.jarvis.voice.dlog("choice: attempt ${attempt + 1}, transcribed=${text != null}, parsed=$choice")
                        if (choice != null || auth.prompt.value?.id != p.id) break
                    }
                    when (choice) {
                        SpokenChoice.Choice.PIN -> auth.choose(AuthMethod.PIN)
                        SpokenChoice.Choice.FINGERPRINT -> auth.choose(AuthMethod.BIOMETRIC)
                        null -> Unit
                    }
                }
            }
        }
    }

    fun interrupt() = conversation.interrupt()

    /** "Not for me" is offered when this wake came from the wake model with the personal check on. */
    val canLearn: Boolean = bus.lastWakeFeatures.value?.isNotEmpty() == true && heyJarvis.hasVerifier()

    /** The wake was a mistake: stop, and teach the personal check to ignore that moment (in the background). */
    fun notForMe() {
        conversation.interrupt()
        bus.lastWakeFeatures.value?.takeIf { it.isNotEmpty() }?.let { heyJarvis.learnFalseWake(it) }
        bus.lastWakeFeatures.value = null
    }

    /** Mic button: stop talking while speaking; finish listening while listening. */
    fun micTapped() {
        when (phase.value) {
            WakePhase.SPEAKING -> conversation.interrupt()
            WakePhase.LISTENING -> stt.stopListening()
            else -> Unit
        }
    }
}

/**
 * The "Hey Jarvis" screen: full-screen black over everything (including the lock screen), with the system bars
 * hidden; turns the screen on, runs one voice exchange with the wake animation, then fades out and closes.
 * Tap anywhere to stop talking; ✕ to close.
 */
@AndroidEntryPoint
class WakeActivity : ComponentActivity() {
    @Inject lateinit var auth: AuthCoordinator
    @Inject lateinit var bus: WakeBus
    @Inject lateinit var biometric: AndroidBiometricAvailability
    private val vm: WakeViewModel by viewModels()

    private val biometricLauncher = registerForAuthenticationResult(
        object : AuthenticationResultCallback {
            override fun onAuthResult(result: AuthenticationResult) = auth.onBiometricResult(BiometricGate.outcome(result))
            override fun onAuthAttemptFailed() = Unit
        }
    )
    private var launchedPromptId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Immersive: nothing but black, the orb and words (swipe from an edge shows the bars briefly).
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        bus.wakeScreenShown.value = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    auth.prompt.collect { p ->
                        if (p?.stage == AuthPrompt.Stage.BIOMETRIC && p.id != launchedPromptId) {
                            launchedPromptId = p.id
                            biometricLauncher.launch(BiometricGate.request(biometric.status(), p.pinAvailable && !p.pinLocked, p.message))
                        }
                    }
                }
                vm.phase.first { it == WakePhase.DONE }
                delay(500) // let the fade-out play
                finish()
            }
        }
        setContent {
            JarvisTheme(dark = true) {
                val phase by vm.phase.collectAsStateWithLifecycle()
                val transcript by vm.transcript.collectAsStateWithLifecycle()
                val reply by vm.reply.collectAsStateWithLifecycle()
                val level by vm.level.collectAsStateWithLifecycle()
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { if (phase == WakePhase.SPEAKING) vm.interrupt() },
                ) {
                    WakeAnimation(
                        phase, level, transcript, reply,
                        onMic = vm::micTapped,
                        onClose = { vm.interrupt(); finish() },
                        onNotForMe = if (vm.canLearn) ({
                            vm.notForMe()
                            android.widget.Toast.makeText(this@WakeActivity, "Got it. I'll ignore that next time.", android.widget.Toast.LENGTH_SHORT).show()
                            finish()
                        }) else null,
                    )
                }
                AuthHost(auth)
            }
        }
    }

    companion object {
        const val EXTRA_QUESTION = "question"
        fun intent(context: android.content.Context, question: String? = null): android.content.Intent =
            android.content.Intent(context, WakeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .apply { if (!question.isNullOrBlank()) putExtra(EXTRA_QUESTION, question) }
    }

    override fun onDestroy() {
        if (isFinishing) {
            vm.interrupt()
            bus.conversationActive.value = false
        }
        super.onDestroy()
    }
}
