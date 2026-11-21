package dev.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** One push-to-talk recognition session, as events. */
sealed interface SttEvent {
    data object Ready : SttEvent
    /** Rough input level, 0..1, for the listening animation. */
    data class Level(val value: Float) : SttEvent
    data class Partial(val text: String, val alternatives: List<String> = emptyList()) : SttEvent
    /** [alternatives] = the recognizer's other guesses (n-best), when asked for with maxResults > 1. */
    data class Final(val text: String, val alternatives: List<String> = emptyList()) : SttEvent
    data class Failed(val error: SttError) : SttEvent
}

enum class SttError { NO_SPEECH, NO_MATCH, NO_PERMISSION, BUSY, NETWORK, UNAVAILABLE, OTHER }

/** Speech-to-text (CLAUDE.md: swappable Stt interface). */
interface Stt {
    fun isAvailable(): Boolean
    /** Listens once; the flow completes after Final or Failed. Cancelling the collector cancels recognition. */
    fun listen(preferOnDevice: Boolean): Flow<SttEvent>
    /** Like [listen] but asks for up to [maxResults] guesses per result (for the wake word and its training). */
    fun listen(preferOnDevice: Boolean, maxResults: Int): Flow<SttEvent> = listen(preferOnDevice)
    /** Stop listening and recognise what was said so far (the user tapped "done"). */
    fun stopListening()
    /** Transcribes a 16 kHz mono recording; null if unsupported or nothing recognised. */
    suspend fun transcribe(pcm: ShortArray, preferOnDevice: Boolean): String? = null
    /** Like [transcribe] but only with the on-device recognizer; null if the phone has none (for spoken PINs). */
    suspend fun transcribeOnDeviceOnly(pcm: ShortArray): String? = null
}

/**
 * Android SpeechRecognizer. Must run on the main thread (platform rule), so the flow is pinned to
 * Dispatchers.Main. Prefers the on-device recognizer (Android 12+), which keeps audio on the phone;
 * if its language isn't downloaded it falls back to the default recognizer for that session.
 */
class AndroidStt(private val context: Context) : Stt {
    private var active: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun stopListening() { active?.stopListening() }

    override fun listen(preferOnDevice: Boolean): Flow<SttEvent> = listen(preferOnDevice, 1)

    override fun listen(preferOnDevice: Boolean, maxResults: Int): Flow<SttEvent> = callbackFlow {
        var onDevice = preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        var recognizer: SpeechRecognizer? = null

        fun start() {
            recognizer?.destroy()
            val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            active = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { trySend(SttEvent.Ready) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { trySend(SttEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    best(partialResults)?.let { trySend(SttEvent.Partial(it, all(partialResults))) }
                }
                override fun onResults(results: Bundle?) {
                    val text = best(results)
                    trySend(if (text.isNullOrBlank()) SttEvent.Failed(SttError.NO_MATCH) else SttEvent.Final(text, all(results)))
                    close()
                }
                override fun onError(error: Int) {
                    // On-device language not installed: retry once with the default recognizer.
                    if (onDevice && error in RETRY_WITH_DEFAULT) {
                        onDevice = false
                        start()
                        return
                    }
                    trySend(SttEvent.Failed(map(error)))
                    close()
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            r.startListening(intent(maxResults))
        }

        start()
        awaitClose {
            recognizer?.cancel()
            recognizer?.destroy()
            if (active === recognizer) active = null
        }
    }.flowOn(Dispatchers.Main)

    /**
     * Feeds a recording to the recognizer through a pipe (EXTRA_AUDIO_SOURCE, Android 13+), so a question said in
     * the same breath as "Hey Jarvis" isn't lost. Recognizers that don't support audio sources fail fast (null).
     */
    override suspend fun transcribeOnDeviceOnly(pcm: ShortArray): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            transcribe(pcm, preferOnDevice = true) else null

    override suspend fun transcribe(pcm: ShortArray, preferOnDevice: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || pcm.isEmpty()) return null
        return kotlinx.coroutines.withTimeoutOrNull(8_000) {
            callbackFlow<String?> {
                val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
                val (readSide, writeSide) = android.os.ParcelFileDescriptor.createPipe()
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onResults(results: Bundle?) { trySend(best(results)); close() }
                    override fun onError(error: Int) { trySend(null); close() }
                })
                val intent = intent(1).apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                r.startListening(intent)
                // Write the recording, then a second of silence so the recognizer ends the utterance, then close.
                val writer = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                            val bytes = java.nio.ByteBuffer.allocate((pcm.size + 16_000) * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            pcm.forEach { bytes.putShort(it) }
                            out.write(bytes.array())
                        }
                    }
                }
                awaitClose {
                    writer.cancel()
                    runCatching { readSide.close() }
                    r.cancel(); r.destroy()
                }
            }.flowOn(Dispatchers.Main).firstOrNull()
        }
    }

    private fun intent(maxResults: Int) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults.coerceIn(1, 10))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
            // No EXTRA_BIASING_STRINGS: on the user's phone (Android System Intelligence / SODA) it made recognition
            // return "no speech" for clearly spoken answers (2026-11-21).
        }
    }

    private fun all(b: Bundle?): List<String> =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun map(error: Int): SttError = when (error) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.NO_SPEECH
        SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_MATCH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.NO_PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttError.BUSY
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SttError.NETWORK
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error in setOf(
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            )) SttError.UNAVAILABLE else SttError.OTHER
    }

    private companion object {
        /** API 31 error codes 12 (not supported), 13 (unavailable / not downloaded), 11 (service disconnected). */
        val RETRY_WITH_DEFAULT = setOf(11, 12, 13)
        val BIAS_WORDS = arrayListOf(
            "Jarvis", "net worth", "my net worth", "portfolio", "SIP", "SIPs", "mutual fund", "INDmoney",
            "what's the time", "battery", "calendar", "lock", "PIN", "fingerprint",
        )
    }
}
