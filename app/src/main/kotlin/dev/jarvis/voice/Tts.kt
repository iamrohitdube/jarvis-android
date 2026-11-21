package dev.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Text-to-speech (CLAUDE.md: swappable Tts interface). */
interface Tts {
    /** True while anything is queued or playing. */
    val speaking: StateFlow<Boolean>
    /** Queues one sentence after anything already queued. */
    fun speak(text: String)
    /** Stops now and drops the queue (barge-in, Stop, quiet mode). */
    fun stop()
    fun setRate(rate: Float)
    /** Pocket mode with a Bluetooth headset: speak on the call route so audio follows the headset (HFP/LE Audio). */
    fun setCallRoute(enabled: Boolean) {}
}

/**
 * Android TextToSpeech with an "assistant" audio stream. While speaking it holds transient audio focus
 * with ducking, so music dips instead of stopping. Utterance callbacks can arrive on any thread, so
 * state is kept in thread-safe types.
 */
class AndroidTts(context: Context) : Tts {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            // Another app took focus permanently (e.g. a call): stop talking.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking
    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)
    @Volatile private var ready = false
    private val early = mutableListOf<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val queued = synchronized(early) { early.toList().also { early.clear() } }
            queued.forEach(::speak)
        }
    }

    init {
        tts.setAudioAttributes(attributes)
        runCatching {
            val locale = Locale.getDefault()
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in API 21") override fun onError(utteranceId: String?) = finished()
            override fun onError(utteranceId: String?, errorCode: Int) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) { synchronized(early) { early += text }; return }
        if (pending.getAndIncrement() == 0) {
            audio?.requestAudioFocus(focus)
            _speaking.value = true
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvis-${ids.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) finished()
    }

    override fun stop() {
        synchronized(early) { early.clear() }
        tts.stop()
        pending.set(0)
        release()
    }

    override fun setRate(rate: Float) { tts.setSpeechRate(rate.coerceIn(0.5f, 2f)) }

    override fun setCallRoute(enabled: Boolean) {
        tts.setAudioAttributes(if (enabled) callAttributes else attributes)
    }

    private val callAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            release()
        }
    }

    private fun release() {
        _speaking.value = false
        audio?.abandonAudioFocusRequest(focus)
    }
}
