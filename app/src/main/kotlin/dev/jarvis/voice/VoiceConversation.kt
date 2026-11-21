package dev.jarvis.voice

import dev.jarvis.agent.AgentEvent
import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.ToolSpec
import dev.jarvis.data.SettingsRepository
import dev.jarvis.memory.HistoryStore
import dev.jarvis.security.AccessController
import dev.jarvis.security.AccessResult
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.DenyReason
import dev.jarvis.security.SessionManager
import dev.jarvis.security.requiredTier
import dev.jarvis.ui.wake.WakePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One "Hey Jarvis" exchange without the chat screen: listen -> agent -> speak, saved to History as its own
 * conversation. Used by the wake screen (phone in hand) and by the headless pocket mode.
 * Speaking rules come from [VoiceSession] (screen-only, locked, quiet mode).
 */
class VoiceConversation(
    private val stt: Stt,
    private val tts: Tts,
    private val agent: AgentLoop,
    private val history: HistoryStore,
    private val settings: SettingsRepository,
    /** Wake acknowledgement (chime / hum / phrase); null = silent (tests). */
    private val cue: WakeCue? = null,
    /** Silent listening on JARVIS's own mic (no recognizer sounds while waiting); null = the recognizer listens (tests). */
    private val capture: VoiceCapture? = null,
    /** Turns captured speech into text (phone recognizer, then on-device Moonshine). Null = [stt] only. */
    private val transcriber: Transcriber? = null,
    private val deviceLocked: () -> Boolean,
) {
    private val _phase = MutableStateFlow(WakePhase.WAKING)
    val phase: StateFlow<WakePhase> = _phase
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript
    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Runs one exchange to completion (including speaking). Returns the recognised question, if any. */
    /** [preheard]: a question already captured with the wake word ("Hey Jarvis, what's the time?"). */
    /** [soundPlayed]: the wake sound already played at the moment of the wake (only a phrase may follow). */
    suspend fun runOnce(scope: CoroutineScope, preheard: String? = null, soundPlayed: Boolean = false): String? {
        // A child scope for this exchange only, so the voice session's watcher ends with it.
        val exchange = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        try {
            return exchange(exchange, preheard, soundPlayed)
        } finally {
            exchange.cancel()
        }
    }

    private suspend fun exchange(scope: CoroutineScope, preheard: String?, soundPlayed: Boolean = false): String? {
        val s = settings.current()
        val voice = VoiceSession(stt, tts, scope, deviceLocked).apply {
            speakReplies = s.speakReplies
            preferOnDevice = s.preferOnDeviceStt
        }
        tts.setRate(s.speechRate)
        // Let the user know JARVIS woke up (they may not be looking at the screen).
        cue?.let { c ->
            val plan = AckPlan.of(s.wakeAck, s.wakeAckPhrase, !preheard.isNullOrBlank(), s.speakReplies)
            runCatching { c.play(if (soundPlayed) plan.copy(sound = null) else plan, tts) }
        }

        // A conversation: answer, then keep listening for a reply or a new question, with the context so far.
        // It ends after IDLE_LIMIT_MS without speech, or when the user says "stop", "that's all", "thanks"...
        val turns = ArrayList<dev.jarvis.agent.ChatTurn>()
        var conversationId: Long? = null
        var firstQuestion: String? = null
        var next: String? = preheard?.trim()?.takeIf { it.isNotEmpty() }
        var idleMs = 0L
        while (true) {
            val question = next ?: run {
                _phase.value = WakePhase.LISTENING
                val started = System.currentTimeMillis()
                val waitMs = if (turns.isEmpty()) FIRST_WAIT_MS else (IDLE_LIMIT_MS - idleMs).coerceAtLeast(1_000)
                val heard = listenOnce(s.preferOnDeviceStt, waitMs)
                if (heard == null) {
                    // A silent attempt counts at least as long as a recognizer's own timeout (~5 s).
                    idleMs += maxOf(System.currentTimeMillis() - started, SILENT_ATTEMPT_MS)
                }
                heard
            }
            next = null
            if (question.isNullOrEmpty()) {
                // Nothing said: right after the wake that's the end; in a conversation, wait up to the idle limit.
                if (turns.isEmpty() || idleMs >= IDLE_LIMIT_MS) break
                continue
            }
            idleMs = 0
            if (turns.isNotEmpty() && Conversation.isGoodbye(question)) {
                _transcript.value = question
                break
            }
            if (firstQuestion == null) firstQuestion = question
            _transcript.value = question
            _reply.value = ""
            val reply = answer(question, turns, voice, conversationId) { conversationId = it }
            turns += dev.jarvis.agent.ChatTurn(dev.jarvis.agent.ChatTurn.Role.USER, question)
            turns += dev.jarvis.agent.ChatTurn(dev.jarvis.agent.ChatTurn.Role.ASSISTANT, reply.ifBlank { "(no reply)" })
            // Keep the working context short (CLAUDE.md token rules): the last few exchanges only.
            while (turns.size > MAX_TURNS) { turns.removeAt(0); turns.removeAt(0) }
            delay(AFTER_SPEECH_MS) // don't hear the end of our own voice
        }
        _phase.value = WakePhase.DONE
        return firstQuestion
    }

    /** One listening attempt: the final transcript, or null on silence/failure. Shows live words and level. */
    private suspend fun listenOnce(preferOnDevice: Boolean, waitMs: Long): String? {
        capture?.let { mic ->
            // Wait silently for speech, then transcribe it once.
            val pcm = mic.capture(waitMs) { _level.value = it } ?: return null
            _phase.value = WakePhase.THINKING
            val text = transcriber?.transcribe(pcm, preferOnDevice) ?: stt.transcribe(pcm, preferOnDevice)
            return text?.trim()?.takeIf { it.isNotEmpty() }
        }
        val heard = stt.listen(preferOnDevice)
            .onEach { ev ->
                when (ev) {
                    is SttEvent.Level -> _level.value = ev.value
                    is SttEvent.Partial -> _transcript.value = ev.text
                    is SttEvent.Final -> _transcript.value = ev.text
                    else -> Unit
                }
            }
            .firstOrNull { it is SttEvent.Final || it is SttEvent.Failed }
        return (heard as? SttEvent.Final)?.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Sends one question (with the conversation so far), speaks the reply, saves both; returns the reply text. */
    private suspend fun answer(
        question: String,
        turns: List<dev.jarvis.agent.ChatTurn>,
        voice: VoiceSession,
        conversationId: Long?,
        onConversation: (Long) -> Unit,
    ): String {
        _phase.value = WakePhase.THINKING
        voice.beginTurn(voice = true)
        val id = conversationId ?: history.startConversation(question).also(onConversation)
        history.addUserMessage(id, question)
        var replyText = ""
        agent.run(turns.toList(), question, settings.current()).collect { ev ->
            when (ev) {
                is AgentEvent.Authorized -> voice.onAuthorized(ev.screenOnly, ev.sensitive, ev.byPin)
                is AgentEvent.TextDelta -> {
                    _reply.value += ev.text
                    voice.onTextDelta(ev.text)
                    if (tts.speaking.value) _phase.value = WakePhase.SPEAKING
                }
                is AgentEvent.Finished -> {
                    replyText = ev.text
                    history.addAssistantMessage(
                        id, ev.text, ev.model, ev.costUsd, ev.dataStamps,
                        sensitive = ev.sensitive, screenOnly = ev.screenOnly,
                    )
                    voice.onFinished()
                }
                is AgentEvent.Failed -> {
                    replyText = ev.partialText
                    history.addAssistantMessage(id, ev.partialText, failed = true)
                    val reason = (ev.error as? dev.jarvis.agent.AgentError.AccessDenied)?.reason
                    voice.onFailed(if (reason == DenyReason.NEEDS_SCREEN) NEEDS_SCREEN_LINE else VoiceSession.FAILED_LINE)
                }
                else -> Unit
            }
        }
        // Let the answer finish speaking (bounded, in case the engine never reports completion).
        if (tts.speaking.value) _phase.value = WakePhase.SPEAKING
        withTimeoutOrNull(SPEAK_TIMEOUT_MS) { tts.speaking.first { !it } }
        return replyText
    }

    /** Stop talking now (tap on the wake screen). */
    fun interrupt() = tts.stop()

    companion object {
        const val NEEDS_SCREEN_LINE = "That's private. Take your phone out and ask me again."
        private const val SPEAK_TIMEOUT_MS = 90_000L
        /** Go back to sleep after this long without speech. */
        const val IDLE_LIMIT_MS = 15_000L
        const val SILENT_ATTEMPT_MS = 5_000L
        /** Right after the wake (the user paused): how long to wait for the question. */
        private const val FIRST_WAIT_MS = 7_000L
        private const val AFTER_SPEECH_MS = 250L
        /** Context kept within one voice conversation: the last 6 exchanges. */
        private const val MAX_TURNS = 12
    }
}

/**
 * Access rules when there's no screen to confirm on (phone in a pocket, or locked): personal and sensitive
 * tools are refused with a spoken "take your phone out", unless a valid session already covers them and the
 * phone is unlocked. Open tools (public market data, general questions) work as normal.
 */
class HeadlessAccess(
    private val delegate: AccessController,
    private val sessions: SessionManager,
    private val sessionPolicy: suspend () -> dev.jarvis.security.SessionPolicy,
    private val deviceLocked: () -> Boolean,
    /** Spoken PIN is set up and not locked out: then private questions can be unlocked by voice, without a screen. */
    private val spokenPinReady: suspend () -> Boolean = { false },
) : AccessController {
    override suspend fun authorize(tools: List<ToolSpec>, preferred: AuthMethod?): AccessResult {
        val tier = tools.requiredTier()
        if (tier >= AuthTier.PERSONAL) {
            if (deviceLocked() || !sessions.isValid(tier, sessionPolicy())) {
                // No screen for a fingerprint: the only way in is the spoken PIN (the user's choice, 2026-11-21).
                if (spokenPinReady()) return delegate.authorize(tools, AuthMethod.PIN)
                return AccessResult.Denied(DenyReason.NEEDS_SCREEN, tools.filterNot { it.helper }.map { it.name })
            }
        }
        return delegate.authorize(tools, preferred)
    }
}

/** Phrases that end a voice conversation. */
object Conversation {
    private val goodbye = Regex(
        "^(ok(ay)? )?(jarvis )?(stop|stop listening|that's all|that is all|that's it|that'll be all|thanks|thank you|" +
            "thanks a lot|thank you so much|bye|bye bye|goodbye|good bye|go to sleep|sleep|never mind|nevermind|nothing|" +
            "cancel|no thanks|no thank you|i'm done|im done|done|shut up|be quiet|quiet)( jarvis)?( thanks| thank you)?$"
    )

    fun isGoodbye(text: String): Boolean =
        goodbye.matches(text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim())
}
