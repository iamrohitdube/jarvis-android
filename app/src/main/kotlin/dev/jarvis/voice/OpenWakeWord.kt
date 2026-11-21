package dev.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * openWakeWord "hey jarvis" (docs/decisions.md D27): a small always-on keyword model, like the detectors behind
 * "Hey Siri" / "Hey Google". It runs on the phone with no recognizer sessions (no beeps); audio never leaves the
 * phone. Code: Apache-2.0 (github.com/dscripka/openWakeWord, ported here). Models: CC BY-NC-SA 4.0, so they're
 * downloaded on demand (pinned hashes), not shipped in the repo or APK.
 */
object OwwModelFiles {
    private const val BASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    val MEL = ModelFile("oww/melspectrogram.tflite", "$BASE/melspectrogram.tflite",
        "96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8", 1_092_516)
    val EMBEDDING = ModelFile("oww/embedding_model.tflite", "$BASE/embedding_model.tflite",
        "c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8", 1_330_312)
    val HEY_JARVIS = ModelFile("oww/hey_jarvis_v0.1.tflite", "$BASE/hey_jarvis_v0.1.tflite",
        "14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2", 1_278_912)
    val ALL = listOf(MEL, EMBEDDING, HEY_JARVIS)
}

/** Debug-build-only diagnostics (numbers and states only, never audio or words). */
internal fun dlog(msg: String) {
    if (dev.jarvis.BuildConfig.DEBUG) android.util.Log.i("JarvisWake", msg)
}

/** The three openWakeWord networks (swappable for tests). Audio is int16-scale floats (±32768), 16 kHz. */
interface OwwModels : AutoCloseable {
    /** Raw mel frames (n x 32) for [samples]. */
    fun mel(samples: FloatArray): Array<FloatArray>
    /** One 96-dim embedding from 76 mel frames. */
    fun embed(window: List<FloatArray>): FloatArray
    /** Wake score 0..1 from the last 16 embeddings. */
    fun classify(features: List<FloatArray>): Float
}

/**
 * Streaming openWakeWord pipeline, ported from openwakeword/utils.py (AudioFeatures) and model.py:
 * every 1280 samples (80 ms) → mel of those samples plus 480 samples of context (8 frames, transformed x/10+2)
 * → one embedding from the last 76 mel frames → score from the last 16 embeddings.
 */
class OwwStream(private val models: OwwModels) {
    private var context = FloatArray(CONTEXT)
    private val pending = FloatArray(STEP)
    private var pendingCount = 0
    private val mels = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var warmup = 0
    private val initialFeatures: List<FloatArray>

    init {
        // As in the reference: the feature buffer starts with embeddings of 4 s of low noise, the mel buffer with ones.
        resetMels()
        val noise = Random(7)
        val clip = FloatArray(16_000 * 4) { (noise.nextInt(-1000, 1000)).toFloat() }
        var i = 0
        while (i + STEP <= clip.size) { step(clip.copyOfRange(i, i + STEP), score = false); i += STEP }
        initialFeatures = features.toList()
        resetMels()
        context = FloatArray(CONTEXT)
    }

    /** Back to the start state (e.g. after a silence), without the noise warm-up cost. */
    fun reset() {
        resetMels()
        context = FloatArray(CONTEXT)
        pendingCount = 0
        features.clear(); features.addAll(initialFeatures)
        warmup = 0
    }

    /** The classifier's current input: the last 16 embeddings (oldest first). */
    fun window(): List<FloatArray> = features.subList(features.size - FRAMES, features.size).map { it.copyOf() }

    /** Feeds audio; returns one score per completed 80 ms step. */
    fun accept(samples: FloatArray): List<Float> {
        val scores = ArrayList<Float>(2)
        var i = 0
        while (i < samples.size) {
            val take = minOf(STEP - pendingCount, samples.size - i)
            samples.copyInto(pending, pendingCount, i, i + take)
            pendingCount += take
            i += take
            if (pendingCount == STEP) {
                scores += step(pending.copyOf(), score = true)
                pendingCount = 0
            }
        }
        return scores
    }

    private fun step(chunk: FloatArray, score: Boolean): Float {
        val input = context + chunk
        context = chunk.copyOfRange(STEP - CONTEXT, STEP)
        for (frame in models.mel(input)) {
            mels.addLast(FloatArray(frame.size) { frame[it] / 10f + 2f })
        }
        while (mels.size > MAX_MELS) mels.removeFirst()
        if (mels.size >= WINDOW) {
            features.addLast(models.embed(mels.subList(mels.size - WINDOW, mels.size)))
            while (features.size > MAX_FEATURES) features.removeFirst()
        }
        if (!score || features.size < FRAMES) return 0f
        val s = models.classify(features.subList(features.size - FRAMES, features.size))
        // Reference: zero predictions for the first 5 frames.
        return if (warmup < 5) { warmup++; 0f } else s
    }

    private fun resetMels() {
        mels.clear()
        repeat(WINDOW) { mels.addLast(FloatArray(32) { 1f }) }
    }

    companion object {
        const val STEP = 1280
        const val CONTEXT = 480
        const val WINDOW = 76
        const val FRAMES = 16
        const val MAX_MELS = 970
        const val MAX_FEATURES = 120
    }
}

/**
 * What the user says straight after "Hey Jarvis": 80 ms chunks are collected until they stop talking.
 * - The first [skipMs] are recorded but ignored for speech detection (the tail of "...vis" and the wake sound).
 * - No speech within [waitMs] after that = they paused: no follow-up (JARVIS chimes and listens instead).
 * - Otherwise it ends after [silenceMs] of quiet, or at [maxMs].
 */
class FollowUpCapture(
    private val speechLevel: Float,
    /** Room loudness measured just before the wake phrase. */
    initialAmbient: Float = speechLevel / 3f,
    private val skipMs: Int = 800,
    private val waitMs: Int = 600,
    private val silenceMs: Int = 640,
    private val maxMs: Int = 8_000,
) {
    private val chunks = ArrayList<ShortArray>()
    private var elapsedMs = 0
    private var heardSpeech = false
    private var quietMs = 0
    /** Background loudness (a slowly rising minimum), so a fan or traffic doesn't count as speech. */
    private var ambient = initialAmbient
    /** Loudest level of the follow-up; quiet = well below it. */
    private var peak = 0f
    var done = false
        private set

    fun offer(chunk: ShortArray) {
        if (done) return
        chunks += chunk
        val ms = chunk.size * 1000 / 16_000
        elapsedMs += ms
        val level = kotlin.math.sqrt(chunk.fold(0.0) { a, s -> a + (s / 32768.0) * (s / 32768.0) } / chunk.size.coerceAtLeast(1)).toFloat()
        ambient = minOf(ambient * 1.01f, maxOf(level, 1e-5f))
        if (elapsedMs > skipMs) {
            // Tuned for quiet phone mics in a humming room (the user's voice reached only ~1.2-2.5x the room level).
            val speech = level > maxOf(speechLevel, ambient * 1.8f)
            if (speech) { heardSpeech = true; peak = maxOf(peak, level) }
            val quiet = level < maxOf(speechLevel, ambient * 1.4f, peak * 0.3f)
            if (heardSpeech && !quiet) quietMs = 0 else if (heardSpeech) quietMs += ms
        }
        done = when {
            elapsedMs >= maxMs -> true
            !heardSpeech && elapsedMs >= skipMs + waitMs -> true
            heardSpeech && quietMs >= silenceMs -> true
            else -> false
        }
    }

    /** The recording if the user kept talking, else null. */
    fun audio(): ShortArray? {
        if (!heardSpeech) return null
        val out = ShortArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }
}

/** Decides when scores count as a wake: threshold, consecutive frames, and a short refractory period. */
class OwwDetector(private val threshold: Float, private val patience: Int, private val refractorySteps: Int = 25) {
    private var above = 0
    private var cooldown = 0
    var best = 0f
        private set

    fun offer(score: Float): Boolean {
        best = maxOf(best, score)
        if (cooldown > 0) { cooldown--; return false }
        above = if (score >= threshold) above + 1 else 0
        if (above >= patience) { above = 0; cooldown = refractorySteps; return true }
        return false
    }

    fun takeBest(): Float = best.also { best = 0f }

    companion object {
        fun forSensitivity(s: WakeSensitivity) =
            OwwDetector(OpenWakeWordEngine.thresholdOf(s), if (s == WakeSensitivity.STRICT) 2 else 1)

        /**
         * With the personal check the score is its probability, which is near 1 for the user's own "Hey Jarvis"
         * (0.99 in tests) but can reach ~0.7 for someone else's rhyme ("Hey Travis"), so the bar is higher.
         */
        fun forPersonalCheck(s: WakeSensitivity) = when (s) {
            WakeSensitivity.STRICT -> OwwDetector(0.9f, 1)
            WakeSensitivity.NORMAL -> OwwDetector(0.8f, 1)
            WakeSensitivity.SENSITIVE -> OwwDetector(0.7f, 1)
        }
    }
}

/** LiteRT (TensorFlow Lite) runner for the downloaded openWakeWord models, one CPU thread each. */
class LiteRtOwwModels(dir: File) : OwwModels {
    private val opts = Interpreter.Options().setNumThreads(1)
    private val mel = Interpreter(melWithFixedInput(File(dir, OwwModelFiles.MEL.path)), opts)
    private val emb = Interpreter(File(dir, OwwModelFiles.EMBEDDING.path), opts)
    private val cls = Interpreter(File(dir, OwwModelFiles.HEY_JARVIS.path), opts)
    private var melInputSize = MEL_INPUT
    private val embIn = direct(OwwStream.WINDOW * 32)
    private val embOut = direct(96)
    private val clsIn = direct(OwwStream.FRAMES * 96)
    private val clsOut = direct(1)

    override fun mel(samples: FloatArray): Array<FloatArray> {
        if (melInputSize != samples.size) {
            mel.resizeInput(0, intArrayOf(1, samples.size), true)
            mel.allocateTensors()
            melInputSize = samples.size
        }
        val input = direct(samples.size).apply { asFloatBuffer().put(samples) }
        val shape = mel.getOutputTensor(0).shape() // [1, 1, frames, 32]
        val frames = shape[shape.size - 2]
        val out = direct(frames * 32)
        mel.run(input, out)
        out.rewind()
        val fb = out.asFloatBuffer()
        return Array(frames) { FloatArray(32).also { fb.get(it) } }
    }

    override fun embed(window: List<FloatArray>): FloatArray {
        embIn.rewind(); val fb = embIn.asFloatBuffer(); window.forEach { fb.put(it) }
        embOut.rewind()
        emb.run(embIn, embOut)
        embOut.rewind()
        return FloatArray(96).also { embOut.asFloatBuffer().get(it) }
    }

    override fun classify(features: List<FloatArray>): Float {
        clsIn.rewind(); val fb = clsIn.asFloatBuffer(); features.forEach { fb.put(it) }
        clsOut.rewind()
        cls.run(clsIn, clsOut)
        clsOut.rewind()
        return clsOut.asFloatBuffer().get(0)
    }

    override fun close() { mel.close(); emb.close(); cls.close() }

    private fun direct(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    companion object {
        /** Byte offset of the mel model's input shape [1, 1] in the pinned melspectrogram.tflite (v0.5.1). */
        const val MEL_SHAPE_OFFSET = 1_092_456
        const val MEL_INPUT = OwwStream.CONTEXT + OwwStream.STEP // 1760 samples per step

        /**
         * LiteRT's Java Interpreter prepares a model as soon as it's loaded, and the mel model's placeholder input
         * is a single sample, which can't be prepared. So the (hash-verified) file is loaded into memory with its
         * input shape set to the size we always feed, [1, 1760]. The file on disk is untouched.
         */
        fun melWithFixedInput(file: File): ByteBuffer {
            val bytes = file.readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(bytes.size.toLong() == OwwModelFiles.MEL.bytes && bb.getInt(MEL_SHAPE_OFFSET) == 1 && bb.getInt(MEL_SHAPE_OFFSET + 4) == 1) {
                "Unexpected mel model layout"
            }
            bb.putInt(MEL_SHAPE_OFFSET + 4, MEL_INPUT)
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }
}

/**
 * The always-on "Hey Jarvis" engine: reads the plain microphone in 80 ms chunks, keeps the models idle in
 * silence (the energy gate), and emits a detection when the score passes the sensitivity's threshold.
 * No transcript: after the wake, the conversation's recognizer listens for the question.
 */
class OpenWakeWordEngine(
    private val modelsDir: () -> File,
    private val models: (File) -> OwwModels = { LiteRtOwwModels(it) },
) : WakeWordEngine {
    /** The personal voice check, if the user trained one ("Teach JARVIS your voice"). */
    val verifierFile: File get() = File(modelsDir(), "oww/personal_verifier.bin")
    private val trainingFile: File get() = File(modelsDir(), "oww/personal_training.bin")
    fun hasVerifier(): Boolean = verifierFile.exists()
    fun removeVerifier() { verifierFile.delete(); trainingFile.delete() }

    /** "Not for me": learn from a false wake in the background (retrains the personal check from saved examples). */
    fun learnFalseWake(windows: List<FloatArray>, done: (OwwVerifierTrainer.Result?) -> Unit = {}) {
        captureScope.launch {
            val r = runCatching {
                val set = OwwTrainingSet.load(trainingFile) ?: return@runCatching null
                set.addFalseWake(windows)
                val result = set.fit()
                set.save(trainingFile)
                result.verifier.save(verifierFile)
                dlog("verifier: retrained with ${set.hardNegatives.size} false-wake examples")
                result
            }.getOrNull()
            done(r)
        }
    }
    override val name = "openWakeWord \"hey jarvis\" (on-device)"

    override fun isReady(): Boolean = OwwModelFiles.ALL.all { File(modelsDir(), it.path).length() == it.bytes }

    /** Follow-up recordings outlive the detection flow (which ends as soon as the wake is reported). */
    private val captureScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the service before starting.
    override fun detections(sensitivity: WakeSensitivity, diagnostics: WakeDiagnostics?, mic: MicSource): Flow<WakeDetection> = callbackFlow {
        val m = models(modelsDir())
        val stream = OwwStream(m)
        val verifier = OwwVerifier.load(verifierFile)
        val detector = if (verifier != null) OwwDetector.forPersonalCheck(sensitivity) else OwwDetector.forSensitivity(sensitivity)
        dlog("engine: personal check ${if (verifier != null) "on (candidate %.2f)".format(verifier.candidateThreshold) else "off"}")
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            mic.androidSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, OwwStream.STEP * 8),
        )
        // Set when the mic is handed to a follow-up capture: then the capture, not this flow, releases it.
        var handedOver = false
        val loop = launch(Dispatchers.Default) {
            try {
                record.startRecording()
                dlog("mic: state=${record.state} recording=${record.recordingState} source=${mic.name}")
                val pcm = ShortArray(OwwStream.STEP)
                var reads = 0; var peak = 0f; var bestSeen = 0f
                val recentLevels = ArrayDeque<Float>()
                val recentWindows = ArrayDeque<FloatArray>() // feature windows near the wake, for "Not for me"
                // Pre-roll 6 x 80 ms so the start of "hey" isn't lost when the gate opens.
                val gate = sensitivity.gateMinLevel?.let { EnergyGate(minLevel = it, hangoverChunks = 15, preRollChunks = 6) }
                var steps = 0
                while (isActive) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n <= 0) { dlog("mic: read returned $n"); kotlinx.coroutines.delay(200); continue }
                    val chunk = FloatArray(n) { pcm[it].toFloat() }
                    val level = EnergyGate.rms(FloatArray(n) { pcm[it] / 32768f })
                    val feed: List<FloatArray> = if (gate == null) listOf(chunk) else {
                        val wasOpen = gate.open
                        val r = gate.process(chunk, level)
                        if (!wasOpen && gate.open) stream.reset() // fresh state for each burst of sound
                        r.feed
                    }
                    diagnostics?.onAudio(level, gate?.open ?: true, 1f)
                    reads++; peak = maxOf(peak, level)
                    recentLevels.addLast(level); if (recentLevels.size > 25) recentLevels.removeFirst() // ~2 s
                    if (reads % 60 == 0) { dlog("mic: %d reads, peak level %.4f, gate %s, best score %.2f".format(reads, peak, gate?.open, bestSeen)); peak = 0f; bestSeen = 0f }
                    for (c in feed) for (base in stream.accept(c)) {
                        // With the personal check: the base model only nominates; the check decides.
                        val nominated = verifier != null && base >= verifier.candidateThreshold
                        if (nominated || verifier == null && base >= 0.1f) {
                            recentWindows.addLast(OwwVerifier.flatten(stream.window()))
                            if (recentWindows.size > 4) recentWindows.removeFirst()
                        }
                        val score = if (verifier == null) base
                            else if (nominated) verifier.probability(stream.window()) else 0f
                        bestSeen = maxOf(bestSeen, score)
                        if (detector.offer(score)) {
                            dlog("wake: score %.2f (base %.2f)".format(score, base))
                            diagnostics?.onScore(detector.takeBest())
                            stream.reset()
                            val room = recentLevels.minOrNull() ?: 0.001f
                            // Report the wake at once (screen + sound), and keep recording what follows meanwhile.
                            val follow = kotlinx.coroutines.CompletableDeferred<ShortArray?>()
                            handedOver = true
                            captureScope.launch {
                                try {
                                    val audio = captureFollowUp(record, ShortArray(OwwStream.STEP), FollowUpCapture(speechLevel = 0.002f, initialAmbient = room))
                                    dlog("wake: follow-up ${audio?.let { "%.1f s".format(it.size / 16_000f) } ?: "none (paused)"}")
                                    follow.complete(audio)
                                } catch (e: Throwable) {
                                    follow.complete(null)
                                } finally {
                                    runCatching { record.stop() }
                                    runCatching { record.release() }
                                    m.close()
                                }
                            }
                            trySend(WakeDetection("HEY_JARVIS", System.currentTimeMillis(), followUpAudio = follow, features = recentWindows.toList()))
                            return@launch
                        }
                    }
                    if (++steps % 12 == 0) diagnostics?.onScore(detector.takeBest()) // about once a second
                }
            } catch (e: Throwable) {
                dlog("engine stopped: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                if (!handedOver) {
                    runCatching { record.release() }
                    m.close()
                }
            }
        }
        awaitClose {
            loop.cancel()
            if (!handedOver) runCatching { record.stop() } // unblocks a pending read
        }
    }

    /**
     * Trains and saves the personal voice check from the user's recordings (-1..1 floats, 16 kHz):
     * [wakeClips] = "Hey Jarvis" said a few times; [otherClips] = ordinary speech and room sound.
     * Each "Hey Jarvis" is analysed at 4 small time offsets (like openWakeWord's training) for more examples.
     */
    fun trainVerifier(wakeClips: List<FloatArray>, otherClips: List<FloatArray>): Pair<OwwVerifierTrainer.Result, List<Float>> {
        models(modelsDir()).use { m ->
            val stream = OwwStream(m)
            fun steps(clip: FloatArray, offset: Int): List<Pair<Float, FloatArray>> {
                stream.reset()
                val audio = FloatArray(SAMPLE_RATE / 2 + offset) + FloatArray(clip.size) { clip[it] * 32767f } + FloatArray(SAMPLE_RATE / 2)
                val out = ArrayList<Pair<Float, FloatArray>>()
                var i = 0
                while (i + OwwStream.STEP <= audio.size) {
                    stream.accept(audio.copyOfRange(i, i + OwwStream.STEP)).forEach { s -> out += s to OwwVerifier.flatten(stream.window()) }
                    i += OwwStream.STEP
                }
                return out
            }
            val positives = ArrayList<FloatArray>(); val negatives = ArrayList<FloatArray>(); val peaks = ArrayList<Float>()
            for (clip in wakeClips) {
                var clipPeak = 0f
                for (offset in listOf(0, 320, 640, 960)) {
                    val st = steps(clip, offset)
                    val top = st.indices.sortedByDescending { st[it].first }.take(3)
                    clipPeak = maxOf(clipPeak, st[top.first()].first)
                    top.forEach { positives += st[it].second }
                }
                peaks += clipPeak
            }
            for (clip in otherClips) steps(clip, 0).forEach { negatives += it.second }
            // Keep earlier false-wake lessons when training again.
            val old = OwwTrainingSet.load(trainingFile)
            val set = OwwTrainingSet(positives, negatives, old?.hardNegatives ?: ArrayList(), peaks).apply { trim() }
            val result = set.fit()
            set.save(trainingFile)
            result.verifier.save(verifierFile)
            return result to peaks
        }
    }

    /** Reads on after the wake phrase until [capture] says the user stopped talking (or never started). */
    private fun captureFollowUp(record: AudioRecord, pcm: ShortArray, capture: FollowUpCapture): ShortArray? {
        while (!capture.done) {
            val n = record.read(pcm, 0, pcm.size)
            if (n <= 0) break
            capture.offer(pcm.copyOf(n))
        }
        return capture.audio()
    }

    /** One-shot check of a recording ("Test now"): returns "score 0.87" style text when it would wake, else null. */
    override fun spot(samples: FloatArray, sensitivity: WakeSensitivity): String? {
        val best = bestScore(samples)
        return if (best >= thresholdOf(sensitivity)) "hey jarvis (score %.2f)".format(best) else null
    }

    /** Highest score over a recording given as -1..1 floats (with silence around it, like live use). */
    fun bestScore(samples: FloatArray): Float {
        models(modelsDir()).use { m ->
            val stream = OwwStream(m)
            val padded = FloatArray(SAMPLE_RATE / 2) + FloatArray(samples.size) { samples[it] * 32767f } + FloatArray(SAMPLE_RATE)
            return stream.accept(padded).maxOrNull() ?: 0f
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        fun thresholdOf(s: WakeSensitivity) = when (s) {
            WakeSensitivity.STRICT -> 0.7f
            WakeSensitivity.NORMAL -> 0.5f
            WakeSensitivity.SENSITIVE -> 0.3f
        }
    }
}
