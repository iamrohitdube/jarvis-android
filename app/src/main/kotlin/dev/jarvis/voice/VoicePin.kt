package dev.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.jarvis.data.SettingsRepository
import dev.jarvis.security.AuthCoordinator
import dev.jarvis.security.AuthPrompt
import dev.jarvis.security.SecretKey
import dev.jarvis.security.SecretStore
import dev.jarvis.security.SpokenPin
import dev.jarvis.security.StrongAuthProof
import dev.jarvis.security.VoiceMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** Where spoken PINs come from (lets the flow be tested without a microphone or models). */
interface SpokenPinSource {
    /** Models downloaded and a voiceprint enrolled. */
    suspend fun ready(): Boolean
    /** Records one spoken PIN; recognises digits and checks the voice, all on the phone. */
    suspend fun capturePin(): SpokenPin
}

/**
 * On-device spoken PIN (CLAUDE.md section 6; D23, D33). Audio is captured by the app itself (never by a cloud
 * recognizer) and turned into digits on the phone (the phone's on-device recognizer, then Moonshine). Nothing is
 * stored; transcripts are never logged. Voice match was removed from the PIN path at the user's request (D33):
 * the voiceprint code below stays only for the later messaging feature (CLAUDE.md section 7).
 */
class VoicePinEngine(
    private val models: ModelDownloader,
    private val secrets: SecretStore,
    /** The microphone path picked in Settings (same as the wake word). */
    private val micSource: suspend () -> MicSource = { MicSource.STANDARD },
    /** A second opinion on the digits from the phone's on-device recognizer (better with accents); null = Moonshine only. */
    private val onDeviceRecognizer: (suspend (ShortArray) -> String?)? = null,
) : VoiceMatcher, SpokenPinSource {

    override suspend fun ready(): Boolean = models.installed()

    override suspend fun isEnrolled(): Boolean = secrets.get(SecretKey.VOICE_PRINT) != null

    override suspend fun enroll(recordings: List<FloatArray>, proof: StrongAuthProof): Boolean = withContext(Dispatchers.Default) {
        val usable = recordings.filter { it.size >= MIN_SAMPLES }
        if (usable.size < recordings.size || usable.isEmpty()) return@withContext false
        val embeddings = withSpeaker { ex -> usable.map { embed(ex, it) } }
        secrets.put(SecretKey.VOICE_PRINT, encode(normalize(average(embeddings))))
        true
    }

    override suspend fun matches(recording: FloatArray): Boolean = (similarity(recording) ?: 0f) >= THRESHOLD

    /** Voice-match score of [recording] against the stored voiceprint (null if none or too short). */
    suspend fun similarity(recording: FloatArray): Float? = withContext(Dispatchers.Default) {
        val stored = secrets.get(SecretKey.VOICE_PRINT)?.let(::decode) ?: return@withContext null
        if (recording.size < MIN_SAMPLES) return@withContext null
        val probe = withSpeaker { embed(it, recording) }
        cosine(stored, normalize(probe))
    }

    override suspend fun remove(proof: StrongAuthProof) = secrets.remove(SecretKey.VOICE_PRINT)

    /** Deletes a voiceprint left from the removed voice-match feature (D33). Deleting weakens nothing now. */
    suspend fun forgetVoiceprint() = secrets.remove(SecretKey.VOICE_PRINT)

    override suspend fun capturePin(): SpokenPin {
        // Skip the first moments so the tail of "Say your PIN." (speaker echo) can't start or end the recording.
        val audio = record(maxMs = 8_000, settleMs = SETTLE_MS) ?: return SpokenPin(null, voiceMatched = true) // nothing heard: no penalty
        if (audio.size < MIN_PIN_SAMPLES) return SpokenPin(null, voiceMatched = true)
        return SpokenPin(digitsOf(audio), voiceMatched = true)
    }

    /**
     * The PIN digits, from two on-device recognizers: the phone's own (on-device only; usually better with accents)
     * and Moonshine. The first clean answer (4-8 digits) wins; if both give digits, the phone's is preferred.
     */
    private suspend fun digitsOf(audio: FloatArray): String? {
        val pcm = ShortArray(audio.size) { (audio[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val phone = onDeviceRecognizer?.let { r -> runCatching { withTimeoutOrNull(RECOGNIZER_TIMEOUT_MS) { r(pcm) } }.getOrNull() }
            ?.let { PinSpeech.digits(it) }
        if (phone != null && phone.length in 4..8) return phone
        val moon = recognizeDigits(audio)
        return if (moon != null && moon.length in 4..8) moon else phone ?: moon
    }

    /** Records one utterance (for PIN or enrolment): starts when speech starts, ends after ~0.8 s of quiet. */
    @SuppressLint("MissingPermission") // callers only reach this after RECORD_AUDIO is granted
    suspend fun record(maxMs: Long = 7_000, settleMs: Long = 0): FloatArray? {
        val source = micSource()
        return recordWith(source, maxMs, settleMs)
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordWith(source: MicSource, maxMs: Long, settleMs: Long): FloatArray? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(source.androidSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 4))
        val gate = EnergyGate(minLevel = 0.0008f, hangoverChunks = 8, preRollChunks = 4)
        val voiced = ArrayList<FloatArray>()
        try {
            rec.startRecording()
            val pcm = ShortArray(CHUNK)
            val started = System.currentTimeMillis()
            val deadline = started + maxMs
            var heard = false
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (System.currentTimeMillis() - started < settleMs) continue
                val r = gate.process(FloatArray(n) { pcm[it] / 32768f })
                if (r.feed.isNotEmpty()) { heard = true; voiced += r.feed }
                if (heard && r.closed) break
            }
            if (!heard) null else AutoGain.normalize(concat(voiced)).first // quiet mics: bring speech to a normal level
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Records exactly [ms] of audio, no gate (diagnostics). Returns raw samples. */
    @SuppressLint("MissingPermission")
    suspend fun recordFor(ms: Long, source: MicSource? = null): FloatArray = withContext(Dispatchers.IO) {
        val src = source ?: micSource()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(src.androidSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CHUNK * 4))
        val total = (SAMPLE_RATE * ms / 1000).toInt()
        val out = FloatArray(total)
        try {
            rec.startRecording()
            val pcm = ShortArray(CHUNK)
            var o = 0
            while (o < total && coroutineContext.isActive) {
                val n = rec.read(pcm, 0, minOf(CHUNK, total - o))
                if (n <= 0) continue
                for (i in 0 until n) out[o + i] = pcm[i] / 32768f
                o += n
            }
            out
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** General on-device transcription with Moonshine, if the spoken-PIN models are downloaded; else null. */
    suspend fun transcribe(audio: FloatArray): String? =
        if (!models.installed()) null else withContext(Dispatchers.Default) { runMoonshine(audio) }?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun recognizeDigits(audio: FloatArray): String? = runMoonshine(audio)?.let { PinSpeech.digits(it) }

    private suspend fun runMoonshine(audio: FloatArray): String? = withContext(Dispatchers.Default) {
        val recognizer = newMoonshine()
        try { decodeWith(recognizer, audio) } finally { recognizer.release() }
    }

    /**
     * Transcribes many pieces with ONE loaded model (long call recordings, D30). Null when the models aren't
     * downloaded. Runs on the phone; text is never logged.
     */
    suspend fun <T> withTranscriber(block: suspend (transcribe: suspend (FloatArray) -> String) -> T): T? {
        if (!models.installed()) return null
        val recognizer = withContext(Dispatchers.Default) { newMoonshine() }
        try {
            return block { audio -> withContext(Dispatchers.Default) { decodeWith(recognizer, audio) } }
        } finally {
            recognizer.release()
        }
    }

    private fun newMoonshine(): OfflineRecognizer {
        val dir = models.dir.absolutePath
        return OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        encoder = "$dir/${VoiceModels.MOON_ENCODER.path}",
                        mergedDecoder = "$dir/${VoiceModels.MOON_DECODER.path}",
                    ),
                    tokens = "$dir/${VoiceModels.MOON_TOKENS.path}",
                    numThreads = 2,
                    debug = false, // never print transcripts
                ),
            ),
        )
    }

    private fun decodeWith(recognizer: OfflineRecognizer, audio: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private inline fun <T> withSpeaker(block: (SpeakerEmbeddingExtractor) -> T): T {
        val ex = SpeakerEmbeddingExtractor(
            null,
            SpeakerEmbeddingExtractorConfig(model = models.file(VoiceModels.SPEAKER).absolutePath, numThreads = 2, debug = false),
        )
        try { return block(ex) } finally { ex.release() }
    }

    private fun embed(ex: SpeakerEmbeddingExtractor, audio: FloatArray): FloatArray {
        val stream = ex.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            stream.inputFinished()
            return ex.compute(stream)
        } finally {
            stream.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK = 1_600
        /** At least ~1 s of voiced audio for a usable voiceprint. */
        const val MIN_SAMPLES = SAMPLE_RATE
        /** A spoken PIN can be short ("one two three four"): ~0.4 s of voiced audio is enough for the digits. */
        const val MIN_PIN_SAMPLES = SAMPLE_RATE * 2 / 5
        /** Audio ignored at the start of a PIN recording (echo of "Say your PIN."). */
        const val SETTLE_MS = 300L
        /** The phone's recognizer sometimes never answers; Moonshine is tried after this. */
        const val RECOGNIZER_TIMEOUT_MS = 4_000L
        /** Cosine similarity needed to accept the voice; sherpa-onnx examples use 0.5-0.6. Tune on the phone. */
        /**
         * Voice-match cut-off (cosine). sherpa-onnx examples use 0.5-0.6; 0.6 rejected the user's own voice on the
         * wake screen (2026-11-21), so 0.5. The PIN is still required on top. Scores are in the audit log to calibrate.
         */
        const val THRESHOLD = 0.5f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
        }

        fun normalize(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x * x
            val len = sqrt(n).toFloat()
            return if (len == 0f) v else FloatArray(v.size) { v[it] / len }
        }

        fun average(vs: List<FloatArray>): FloatArray =
            FloatArray(vs.first().size) { i -> vs.sumOf { it[i].toDouble() }.toFloat() / vs.size }

        fun concat(parts: List<FloatArray>): FloatArray {
            val out = FloatArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            return out
        }

        fun encode(v: FloatArray): String {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        }

        fun decode(s: String): FloatArray? = runCatching {
            val buf = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(buf.remaining() / 4) { buf.getFloat() }
        }.getOrNull()
    }
}

/**
 * In a voice turn, when the PIN stage appears: "Say your PIN." -> record -> check voice + digits on the phone ->
 * hand the result to [AuthCoordinator]. Up to [maxTries] spoken attempts per authentication; typing the PIN in
 * the dialog always works too. Does nothing until spoken PIN is set up (models + voiceprint) or in quiet mode.
 */
class VoicePinFlow(
    private val source: SpokenPinSource,
    private val tts: Tts,
    private val auth: AuthCoordinator,
    private val settings: SettingsRepository,
    /** Set while recording so the wake-word detector (and anything else) releases the microphone. */
    private val micBusy: MutableStateFlow<Boolean>? = null,
    private val maxTries: Int = 3,
) {
    private var lastPromptId = -1L
    private var tries = 0
    private var explained = false

    suspend fun onPrompt(p: AuthPrompt?, voiceTurn: Boolean) {
        if (p == null || p.stage == AuthPrompt.Stage.CHOOSE) { tries = 0; explained = false; return }
        if (p.stage != AuthPrompt.Stage.PIN || p.id == lastPromptId) return
        lastPromptId = p.id
        if (!voiceTurn || !settings.current().speakReplies) return
        if (!source.ready()) {
            // Don't leave the user waiting in silence: say why, once.
            if (!explained) { explained = true; tts.speak(NOT_SET_UP) }
            return
        }
        if (tries >= maxTries) {
            if (!explained) { explained = true; tts.speak(GIVE_UP) }
            return
        }
        tries++
        speakAndWait(if (tries == 1) SAY_PIN else (p.message ?: SAY_AGAIN))
        micBusy?.value = true
        val result = try {
            delay(MIC_HANDOVER_MS) // let the detector or recognizer release the microphone
            source.capturePin()
        } finally {
            micBusy?.value = false
        }
        if (auth.prompt.value?.id == p.id) auth.submitSpokenPin(result)
    }

    private suspend fun speakAndWait(text: String) {
        tts.speak(text)
        withTimeoutOrNull(1_500) { tts.speaking.first { it } }  // started (skip if speech is off or failed)
        withTimeoutOrNull(8_000) { tts.speaking.first { !it } } // finished
    }

    companion object {
        const val SAY_PIN = "Say your PIN."
        const val SAY_AGAIN = "Say it again."
        const val NOT_SET_UP = "Spoken PIN isn't set up. Type your PIN, or use fingerprint."
        const val GIVE_UP = "I couldn't get it. Type your PIN, or use fingerprint."
        const val MIC_HANDOVER_MS = 250L
    }
}
