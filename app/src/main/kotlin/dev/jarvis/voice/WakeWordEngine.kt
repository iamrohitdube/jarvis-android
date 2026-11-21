package dev.jarvis.voice

import kotlinx.coroutines.flow.Flow

/**
 * Always-on, on-device wake-word detection (CLAUDE.md section 1: swappable WakeWordEngine).
 * Implementations must never send audio off the phone, and must release the microphone when stopped so
 * SpeechRecognizer can take over for the conversation.
 */
interface WakeWordEngine {
    /** Human-readable name for Settings/Diagnostics. */
    val name: String

    /** Whether the engine is ready to run (models present, key configured if it needs one). */
    fun isReady(): Boolean

    /** Starts listening; emits once per detection. Cancelling the collector stops the engine and frees the mic. */
    fun detections(
        sensitivity: WakeSensitivity = WakeSensitivity.NORMAL,
        diagnostics: WakeDiagnostics? = null,
        mic: MicSource = MicSource.STANDARD,
    ): Flow<WakeDetection>

    /** Diagnostic: run the detector once on a recording; returns the matched phrase or null. */
    fun spot(samples: FloatArray, sensitivity: WakeSensitivity): String? = null
}

/**
 * How eager the detector is. Values are starting points to tune on a real phone (docs/decisions.md D21).
 * SENSITIVE also turns off the silence gate, so the model always listens (more battery, fewer misses).
 */
enum class WakeSensitivity(val boost: Float, val threshold: Float, val gateMinLevel: Float?) {
    // Gate levels are raw (pre-boost) loudness: phone mics on this path can be very quiet.
    STRICT(1.5f, 0.35f, 0.002f),
    NORMAL(2.5f, 0.20f, 0.0006f),
    SENSITIVE(3.5f, 0.10f, null),
}

/** Live numbers for the Settings diagnostics panel (no audio leaves the engine). */
interface WakeDiagnostics {
    /** [level] = loudness 0..1 of the last 100 ms; [modelActive] = the keyword model is analysing. */
    fun onAudio(level: Float, modelActive: Boolean, gain: Float)
    /** Best wake score (0..1) recently seen by a scoring model (openWakeWord), for the Settings meter. */
    fun onScore(score: Float) {}
}

/** [followUp] = words said right after the wake word in the same breath (used as the question), if any. */
/**
 * [followUp] = the question as text, when the engine transcribes (phone recognition);
 * [followUpAudio] = 16 kHz PCM of what was said straight after the wake phrase, when a keyword model heard
 * the user keep talking ("Hey Jarvis, what's the time?"). Null = the user paused after the wake phrase.
 */
data class WakeDetection(
    val keyword: String,
    val atMs: Long,
    val followUp: String? = null,
    /** Completes when the user stops talking after the wake phrase (null = they paused). Emitted at once. */
    val followUpAudio: kotlinx.coroutines.Deferred<ShortArray?>? = null,
    /** The model's feature windows around the wake (for "Not for me" learning); no audio. */
    val features: List<FloatArray>? = null,
)

/** Which detector listens for "Hey Jarvis". */
enum class WakeEngineChoice(val label: String) {
    /** openWakeWord "hey jarvis" (default): small always-on keyword model, no recognizer sessions (D27). */
    HEY_JARVIS("Wake model"),
    /** The phone's own speech recognizer: works with any voice, but beeps and costs more battery (D25). */
    PHONE("Phone speech"),
    /** The bundled on-device keyword model (sherpa-onnx); lighter on battery, but didn't recognise the user. */
    KEYWORD_MODEL("Old model"),
}

/** In-pocket detection, so the wake flow knows whether to light up the screen. */
fun interface PocketDetector {
    /** True when the proximity sensor is covered (phone in a pocket/bag or face-down against something). */
    suspend fun inPocket(): Boolean
}
