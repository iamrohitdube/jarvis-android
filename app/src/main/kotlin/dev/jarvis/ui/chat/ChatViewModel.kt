package dev.jarvis.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jarvis.agent.AgentEvent
import dev.jarvis.agent.AgentLoop
import dev.jarvis.agent.DataStamp
import dev.jarvis.agent.AuthTier
import dev.jarvis.agent.ToolRegistry
import dev.jarvis.security.AndroidBiometricAvailability
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthMethod
import dev.jarvis.security.AuthPrompt
import dev.jarvis.voice.SpokenChoice
import dev.jarvis.voice.Stt
import dev.jarvis.voice.SttError
import dev.jarvis.voice.Tts
import dev.jarvis.voice.VoiceSession
import dev.jarvis.voice.VoiceState
import dev.jarvis.security.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import dev.jarvis.claude.BudgetLevel
import dev.jarvis.claude.UsageMeter
import dev.jarvis.claude.UsageSummary
import dev.jarvis.data.SettingsRepository
import dev.jarvis.data.db.ConversationSummary
import dev.jarvis.data.db.MessageEntity
import kotlinx.coroutines.flow.map
import dev.jarvis.memory.HistoryStore
import dev.jarvis.ui.common.UiError
import dev.jarvis.ui.common.dataLabel
import dev.jarvis.ui.common.toUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class UiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    /** Short status line, e.g. "Checking INDmoney…" or "haiku-5-5 · $0.0003". */
    val status: String? = null,
    /** "Last updated" label for connector data used by this reply. */
    val dataLabel: String? = null,
    val error: UiError? = null,
    /** Sensitive reply whose text is hidden until the user unlocks (PIN or fingerprint). */
    val hidden: Boolean = false,
    /** Authorised by fingerprint: shown on screen only, never spoken. */
    val screenOnly: Boolean = false,
)

data class ChatState(
    val messages: List<UiMessage> = emptyList(),
    val busy: Boolean = false,
)

/** The reply currently streaming (not yet in Room). */
private data class LiveReply(val text: String = "", val status: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: AgentLoop,
    private val settings: SettingsRepository,
    private val history: HistoryStore,
    private val current: CurrentConversation,
    private val auth: AuthCoordinator,
    private val sessions: SessionManager,
    stt: Stt,
    private val tts: Tts,
    private val device: AndroidBiometricAvailability,
    usageMeter: UsageMeter,
) : ViewModel() {

    /** Push-to-talk pipeline: mic -> STT -> agent -> sentence-by-sentence TTS (CLAUDE.md section 8). */
    private val voice = VoiceSession(stt, tts, viewModelScope, deviceLocked = { device.deviceLocked() })
    val voiceState: StateFlow<VoiceState> = voice.state
    val voicePartial: StateFlow<String> = voice.partial
    val voiceLevel: StateFlow<Float> = voice.level
    val voiceError: StateFlow<SttError?> = voice.error
    val sttAvailable: Boolean = stt.isAvailable()

    /** Quiet mode = not speaking replies (screen only). */
    val quiet: StateFlow<Boolean> = settings.settings.map { !it.speakReplies }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether a sensitive session is active; re-checked every few seconds because sessions expire by time. */
    private val unlocked: Flow<Boolean> = combine(sessions.sessions, ticker(), settings.settings) { _, _, s ->
        sessions.isValid(AuthTier.SENSITIVE, s.security.sessions)
    }.distinctUntilChanged()

    private val live = MutableStateFlow<LiveReply?>(null)
    private val busy = MutableStateFlow(false)
    /** Error (with fix button) for the most recent failed reply only; older failures show a plain label. */
    private val lastError = MutableStateFlow<Pair<Long, UiError>?>(null)
    private var job: Job? = null
    private var lastUserText: String? = null

    private val persisted = current.id.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else history.messages(id) }

    val state: StateFlow<ChatState> = combine(persisted, live, busy, lastError, combine(unlocked, sessions.revealed) { a, b -> a to b }) {
            rows, liveReply, isBusy, err, (open, revealed) ->
        val msgs = rows.map { it.toUi(err, open || it.id in revealed) }.toMutableList()
        liveReply?.let { msgs += UiMessage(LIVE_ID, fromUser = false, text = it.text, streaming = true, status = it.status) }
        ChatState(msgs, isBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    val budget: StateFlow<BudgetLevel> = combine(usageMeter.entries, settings.settings) { entries, s ->
        UsageSummary.of(entries, s.monthlyBudgetUsd, Instant.now(), ZoneId.systemDefault()).budgetLevel
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetLevel.NONE)

    init {
        // Voice preferences follow Settings.
        viewModelScope.launch {
            settings.settings.collect { s ->
                voice.speakReplies = s.speakReplies
                voice.preferOnDevice = s.preferOnDeviceStt
                tts.setRate(s.speechRate)
            }
        }
        // In a voice turn, ask "PIN or fingerprint?" out loud and take a spoken answer.
        viewModelScope.launch {
            var askedFor = -1L
            auth.prompt.collect { p ->
                if (p?.stage == AuthPrompt.Stage.CHOOSE && p.id != askedFor && voice.voiceTurn) {
                    askedFor = p.id
                    voice.ask(VoiceSession.CHOICE_QUESTION) { answer ->
                        when (SpokenChoice.parse(answer)) {
                            SpokenChoice.Choice.PIN -> auth.choose(AuthMethod.PIN)
                            SpokenChoice.Choice.FINGERPRINT -> auth.choose(AuthMethod.BIOMETRIC)
                            null -> Unit // unclear: the 6 s default (fingerprint) applies
                        }
                    }
                } else if (p?.stage != AuthPrompt.Stage.CHOOSE && voice.state.value == VoiceState.LISTENING && askedFor != -1L) {
                    voice.cancelListening() // answered by tap, or moved on
                    askedFor = -1L
                }
            }
        }
        // Whatever is visible while unlocked stays revealed afterwards (until an explicit "lock").
        viewModelScope.launch {
            combine(persisted, unlocked) { a, b -> a to b }.collect { (rows, open) ->
                if (open) sessions.reveal(rows.filter { it.sensitive }.map { it.id })
            }
        }
    }

    /**
     * Mic button: tap to talk; tap again to finish; tap while JARVIS is talking or thinking to interrupt
     * (barge-in) and talk again.
     */
    fun micTapped() {
        when (voice.state.value) {
            VoiceState.LISTENING -> voice.finishListening()
            else -> {
                if (busy.value) stop()
                voice.bargeIn()
                voice.startListening { heard -> send(heard, viaVoice = true) }
            }
        }
    }

    fun cancelListening() = voice.cancelListening()

    /** Tap on the speaking indicator: stop talking (the reply stays on screen). */
    fun stopSpeaking() = voice.bargeIn()

    fun dismissVoiceError() = voice.clearError()

    fun toggleQuiet() = viewModelScope.launch {
        val nowQuiet = !quiet.value
        settings.setSpeakReplies(!nowQuiet)
        if (nowQuiet) voice.bargeIn()
    }

    fun send(raw: String, viaVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty() || busy.value) return
        if (isLockCommand(text)) { lockNow(); if (viaVoice && !quiet.value) tts.speak("Locked."); return }
        voice.beginTurn(viaVoice)
        lastUserText = text
        busy.value = true
        lastError.value = null
        live.value = LiveReply()
        job = viewModelScope.launch {
            var conversationId: Long? = null
            var finished = false
            try {
                val id = current.id.value ?: history.startConversation(text).also { current.open(it) }
                conversationId = id
                val context = history.contextTurns(id) // before adding the new question
                history.addUserMessage(id, text)
                agent.run(context, text, settings.current()).collect { ev ->
                    when (ev) {
                        is AgentEvent.Routed -> live.update { it?.copy(status = routeLabel(ev)) }
                        is AgentEvent.Authorized -> voice.onAuthorized(ev.screenOnly, ev.sensitive)
                        is AgentEvent.ToolActivity -> live.update { it?.copy(status = toolLabel(ev.toolName)) }
                        is AgentEvent.TextDelta -> {
                            live.update { it?.copy(text = it.text + ev.text) }
                            voice.onTextDelta(ev.text)
                        }
                        is AgentEvent.Finished -> {
                            val body = if (ev.truncated) ev.text + "\n\n(Cut off at the length limit. Say \"continue\" for more.)" else ev.text
                            history.addAssistantMessage(
                                id, body, ev.model, ev.costUsd, ev.dataStamps,
                                sensitive = ev.sensitive, screenOnly = ev.screenOnly,
                            )
                            finished = true
                            live.value = null
                            voice.onFinished()
                        }
                        is AgentEvent.Failed -> {
                            val msgId = history.addAssistantMessage(id, ev.partialText, failed = true)
                            lastError.value = msgId to ev.error.toUi()
                            finished = true
                            live.value = null
                            voice.onFailed()
                        }
                    }
                }
            } finally {
                // Stopped mid-stream: keep what arrived, marked as not completed (never resent as context).
                val partial = live.value?.text.orEmpty()
                val id = conversationId
                if (!finished && id != null) {
                    withContext(NonCancellable) { history.addAssistantMessage(id, partial, failed = true) }
                }
                live.value = null
                busy.value = false
            }
        }
    }

    fun retry() { lastUserText?.let(::send) }

    fun stop() {
        voice.bargeIn()
        job?.cancel()
        job = null
    }

    /** "lock": ends every auth session locally; nothing is sent to Claude. */
    private fun lockNow() = viewModelScope.launch {
        auth.lock("voice_or_text_command")
        val id = current.id.value ?: return@launch
        history.addUserMessage(id, "lock")
        history.addAssistantMessage(id, "Locked.", failed = true) // local reply; never resent as context
    }

    /** Tap on a hidden sensitive reply: PIN or fingerprint, then it's visible for the session. */
    fun unlock() = viewModelScope.launch { auth.unlock(AuthTier.SENSITIVE) }

    /** Recent conversations for the navigation drawer. */
    val recent: StateFlow<List<ConversationSummary>> = history.conversations()
        .map { it.take(RECENT_IN_DRAWER) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentId: StateFlow<Long?> = current.id

    /** "Haiku 5.5" style label for the top bar. */
    val modelLabel: StateFlow<String> = settings.settings
        .map { prettyModel(it.chatModel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun open(id: Long) {
        if (busy.value) stop()
        lastError.value = null
        current.open(id)
    }

    fun newChat() {
        if (busy.value) stop()
        lastError.value = null
        current.open(null)
    }

    private fun MessageEntity.toUi(err: Pair<Long, UiError>?, unlocked: Boolean): UiMessage {
        val fromUser = role == HistoryStore.USER
        val error = err?.takeIf { it.first == id }?.second
        val hide = sensitive && !unlocked
        val status = when {
            fromUser -> null
            failed && text == "Locked." -> null
            failed && error == null -> "Not completed"
            failed -> null
            else -> listOfNotNull(model?.removePrefix("claude-"), costUsd?.let { "$" + "%.4f".format(it) }).joinToString(" · ").ifEmpty { null }
        }
        return UiMessage(
            id = id, fromUser = fromUser, text = if (hide) "" else text, status = status,
            dataLabel = dataLabel(DataStamp.decode(dataSources)), error = error,
            hidden = hide, screenOnly = screenOnly,
        )
    }

    private fun routeLabel(ev: AgentEvent.Routed): String {
        val short = ev.model.removePrefix("claude-")
        return when {
            ev.fromCache -> "$short · using saved INDmoney data"
            ev.toolNames.isEmpty() -> short
            else -> "$short · ${ev.toolNames.size} tool${if (ev.toolNames.size > 1) "s" else ""}"
        }
    }

    private fun toolLabel(name: String): String {
        val spec = ToolRegistry.byName(name)
        return if (spec?.connectorId == ToolRegistry.INDMONEY) "Checking INDmoney…" else "Using $name…"
    }

    private companion object {
        const val LIVE_ID = -1L
        const val RECENT_IN_DRAWER = 12
        fun prettyModel(id: String): String = id.removePrefix("claude-").split("-").let { parts ->
            val name = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: id
            val version = parts.drop(1).joinToString(".")
            if (version.isEmpty()) name else "$name $version"
        }
        private val LOCK_COMMANDS = setOf("lock", "lock it", "lock now", "lock up", "lock jarvis")
        fun isLockCommand(text: String) = text.lowercase().trim().trimEnd('.', '!') in LOCK_COMMANDS
        fun ticker(periodMs: Long = 5_000) = flow { while (true) { emit(Unit); delay(periodMs) } }
    }
}
