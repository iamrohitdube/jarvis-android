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
    suspend fun runOnce(scope: CoroutineScope, preheard: String? = null): String? {
        // A child scope for this exchange only, so the voice session's watcher ends with it.
        val exchange = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        try {
            return exchange(exchange, preheard)
        } finally {
            exchange.cancel()
        }
    }

    private suspend fun exchange(scope: CoroutineScope, preheard: String?): String? {
        val s = settings.current()
        val voice = VoiceSession(stt, tts, scope, deviceLocked).apply {
            speakReplies = s.speakReplies
            preferOnDevice = s.preferOnDeviceStt
        }
        tts.setRate(s.speechRate)
        // Let the user know JARVIS woke up (they may not be looking at the screen).
        cue?.let { c ->
            runCatching { c.play(AckPlan.of(s.wakeAck, s.wakeAckPhrase, !preheard.isNullOrBlank(), s.speakReplies), tts) }
        }

        val question = if (!preheard.isNullOrBlank()) {
            _transcript.value = preheard
            preheard.trim()
        } else {
            _phase.value = WakePhase.LISTENING
            val heard = stt.listen(s.preferOnDeviceStt)
                .onEach { ev ->
                    when (ev) {
                        is SttEvent.Level -> _level.value = ev.value
                        is SttEvent.Partial -> _transcript.value = ev.text
                        is SttEvent.Final -> _transcript.value = ev.text
                        else -> Unit
                    }
                }
                .firstOrNull { it is SttEvent.Final || it is SttEvent.Failed }
            (heard as? SttEvent.Final)?.text?.trim()
        }
        if (question.isNullOrEmpty()) {
            _phase.value = WakePhase.DONE
            return null
        }

        _phase.value = WakePhase.THINKING
        voice.beginTurn(voice = true)
        val conversationId = history.startConversation(question)
        history.addUserMessage(conversationId, question)
        agent.run(emptyList(), question, settings.current()).collect { ev ->
            when (ev) {
                is AgentEvent.Authorized -> voice.onAuthorized(ev.screenOnly, ev.sensitive)
                is AgentEvent.TextDelta -> {
                    _reply.value += ev.text
                    voice.onTextDelta(ev.text)
                    if (tts.speaking.value) _phase.value = WakePhase.SPEAKING
                }
                is AgentEvent.Finished -> {
                    history.addAssistantMessage(
                        conversationId, ev.text, ev.model, ev.costUsd, ev.dataStamps,
                        sensitive = ev.sensitive, screenOnly = ev.screenOnly,
                    )
                    voice.onFinished()
                }
                is AgentEvent.Failed -> {
                    history.addAssistantMessage(conversationId, ev.partialText, failed = true)
                    val reason = (ev.error as? dev.jarvis.agent.AgentError.AccessDenied)?.reason
                    voice.onFailed(if (reason == DenyReason.NEEDS_SCREEN) NEEDS_SCREEN_LINE else VoiceSession.FAILED_LINE)
                }
                else -> Unit
            }
        }
        // Let the answer finish speaking (bounded, in case the engine never reports completion).
        if (tts.speaking.value) _phase.value = WakePhase.SPEAKING
        withTimeoutOrNull(SPEAK_TIMEOUT_MS) { tts.speaking.first { !it } }
        _phase.value = WakePhase.DONE
        return question
    }

    /** Stop talking now (tap on the wake screen). */
    fun interrupt() = tts.stop()

    companion object {
        const val NEEDS_SCREEN_LINE = "That's private. Take your phone out and ask me again."
        private const val SPEAK_TIMEOUT_MS = 90_000L
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
) : AccessController {
    override suspend fun authorize(tools: List<ToolSpec>, preferred: AuthMethod?): AccessResult {
        val tier = tools.requiredTier()
        if (tier >= AuthTier.PERSONAL) {
            if (deviceLocked() || !sessions.isValid(tier, sessionPolicy())) {
                return AccessResult.Denied(DenyReason.NEEDS_SCREEN, tools.filterNot { it.helper }.map { it.name })
            }
        }
        return delegate.authorize(tools, preferred)
    }
}
