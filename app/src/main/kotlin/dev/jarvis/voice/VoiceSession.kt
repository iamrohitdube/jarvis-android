package dev.jarvis.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Push-to-talk voice pipeline (CLAUDE.md section 8): mic -> STT -> agent -> TTS, sentence by sentence.
 *
 * Rules enforced here, not in prompts:
 * - Only voice-initiated turns are spoken; typed questions get on-screen answers.
 * - Quiet mode (speakReplies = false): nothing is spoken.
 * - Fingerprint-authorised (screen-only) replies are never read out: "Done, it's on your screen."
 * - Sensitive replies are never spoken while the phone is locked.
 * - Barge-in: starting to listen, or [bargeIn], stops speech immediately.
 */
class VoiceSession(
    private val stt: Stt,
    private val tts: Tts,
    private val scope: CoroutineScope,
    private val deviceLocked: () -> Boolean,
) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state
    private val _partial = MutableStateFlow("")
    /** Live transcript while listening. */
    val partial: StateFlow<String> = _partial
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _error = MutableStateFlow<SttError?>(null)
    val error: StateFlow<SttError?> = _error

    var speakReplies: Boolean = true
    var preferOnDevice: Boolean = true

    /** True while the current agent turn was started by voice. */
    var voiceTurn: Boolean = false
        private set
    private var screenOnly = false
    private var sensitive = false
    private val chunker = SentenceChunker()
    private var listenJob: Job? = null

    init {
        scope.launch {
            tts.speaking.collect { speaking ->
                if (speaking) _state.value = VoiceState.SPEAKING
                else if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.IDLE
            }
        }
    }

    /** Starts listening (barging in on any speech). [onFinal] gets the recognised text. */
    fun startListening(onFinal: (String) -> Unit) {
        tts.stop()
        chunker.reset()
        listenJob?.cancel()
        _partial.value = ""
        _level.value = 0f
        _error.value = null
        _state.value = VoiceState.LISTENING
        listenJob = scope.launch {
            stt.listen(preferOnDevice).collect { ev ->
                when (ev) {
                    SttEvent.Ready -> Unit
                    is SttEvent.Level -> _level.value = ev.value
                    is SttEvent.Partial -> _partial.value = ev.text
                    is SttEvent.Final -> {
                        _partial.value = ev.text
                        _state.value = VoiceState.THINKING
                        onFinal(ev.text)
                    }
                    is SttEvent.Failed -> {
                        _error.value = ev.error
                        _state.value = VoiceState.IDLE
                    }
                }
            }
            if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.IDLE
        }
    }

    /** "Done": recognise what was said so far. */
    fun finishListening() = stt.stopListening()

    fun cancelListening() {
        listenJob?.cancel()
        listenJob = null
        if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.IDLE
    }

    fun clearError() { _error.value = null }

    // ---- agent turn hooks ----

    fun beginTurn(voice: Boolean) {
        voiceTurn = voice
        screenOnly = false
        sensitive = false
        chunker.reset()
        if (voice) _state.value = VoiceState.THINKING
    }

    fun onAuthorized(screenOnly: Boolean, sensitive: Boolean) {
        this.screenOnly = screenOnly
        this.sensitive = sensitive
    }

    fun onTextDelta(delta: String) {
        if (canSpeakContent()) chunker.feed(delta).forEach(tts::speak)
    }

    fun onFinished() {
        if (!voiceTurn) return
        when {
            !speakReplies -> Unit
            screenOnly -> tts.speak(SCREEN_ONLY_LINE)
            sensitive && deviceLocked() -> tts.speak(LOCKED_LINE)
            else -> chunker.flush().forEach(tts::speak)
        }
        endTurn()
    }

    /** A failed turn: say so briefly (details are on screen). */
    fun onFailed() {
        if (voiceTurn && speakReplies) tts.speak(FAILED_LINE)
        endTurn()
    }

    /** Tap while speaking or thinking: stop talking now. */
    fun bargeIn() {
        tts.stop()
        chunker.reset()
        voiceTurn = false
        if (_state.value != VoiceState.LISTENING) _state.value = VoiceState.IDLE
    }

    /**
     * Asks a question out loud (e.g. "PIN or fingerprint?") and listens once for the answer.
     * Does nothing in quiet mode or outside a voice turn.
     */
    fun ask(question: String, onAnswer: (String) -> Unit) {
        if (!voiceTurn || !speakReplies) return
        tts.speak(question)
        scope.launch {
            tts.speaking.first { it }   // started
            tts.speaking.first { !it }  // finished
            if (voiceTurn) startListening(onAnswer)
        }
    }

    private fun endTurn() {
        voiceTurn = false
        if (_state.value == VoiceState.THINKING) _state.value = if (tts.speaking.value) VoiceState.SPEAKING else VoiceState.IDLE
    }

    private fun canSpeakContent() = voiceTurn && speakReplies && !screenOnly && !(sensitive && deviceLocked())

    companion object {
        const val SCREEN_ONLY_LINE = "Done, it's on your screen."
        const val LOCKED_LINE = "It's on your screen once you unlock."
        const val FAILED_LINE = "Sorry, that didn't work. Details are on your screen."
        const val CHOICE_QUESTION = "That's sensitive. PIN or fingerprint?"
    }
}
