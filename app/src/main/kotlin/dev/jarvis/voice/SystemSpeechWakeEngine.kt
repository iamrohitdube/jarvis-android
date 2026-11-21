package dev.jarvis.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * "Hey Jarvis" using the phone's own speech recognizer (docs/decisions.md D25), chosen because the on-device
 * keyword model didn't recognise the user's voice on their phone. Runs short back-to-back recognition sessions
 * (on-device recognizer preferred) and wakes when the transcript contains "Jarvis". Anything said after it in the
 * same breath ("Hey Jarvis, what's the time?") is handed over as the question.
 *
 * Trade-offs: more battery than a keyword model; if the phone has no on-device recognizer (or the user turns that
 * off), speech near the phone is sent to the phone's default recognizer (usually Google) while listening.
 */
class SystemSpeechWakeEngine(
    private val stt: Stt,
    private val preferOnDevice: suspend () -> Boolean,
    /** Phrases learned from the user's own voice ("Teach JARVIS your voice"). */
    private val learned: suspend () -> List<String> = { emptyList() },
) : WakeWordEngine {
    override val name = "Phone speech recognition"

    override fun isReady(): Boolean = stt.isAvailable()

    override fun detections(sensitivity: WakeSensitivity, diagnostics: WakeDiagnostics?, mic: MicSource): Flow<WakeDetection> = flow {
        var backoff = 0L
        while (true) {
            var heard: String? = null
            var guesses: List<String> = emptyList()
            var failed: SttError? = null
            val phrases = learned()
            stt.listen(preferOnDevice(), NBEST).collect { ev ->
                when (ev) {
                    is SttEvent.Level -> diagnostics?.onAudio(ev.value / 10f, heard != null, 1f)
                    is SttEvent.Partial -> { heard = ev.text; guesses = ev.alternatives; diagnostics?.onAudio(0.02f, true, 1f) }
                    is SttEvent.Final -> { heard = ev.text; guesses = ev.alternatives }
                    is SttEvent.Failed -> failed = ev.error
                    SttEvent.Ready -> Unit
                }
            }
            val text = heard
            // Check every guess the recognizer made, not just the top one.
            val match = text?.let { WakePhrase.matchAny(listOf(it) + guesses, sensitivity, phrases) }
            if (match != null) {
                (diagnostics as? WakeTranscriptSink)?.onHeard(text)
                emit(WakeDetection("JARVIS", System.currentTimeMillis(), followUp = match))
                backoff = 0
            } else {
                if (text != null) (diagnostics as? WakeTranscriptSink)?.onHeard(text)
                // Quick restart after silence; back off if the recognizer is busy or failing.
                backoff = when (failed) {
                    null, SttError.NO_SPEECH, SttError.NO_MATCH -> 0
                    else -> (backoff * 2).coerceIn(1_000, 15_000)
                }
                delay(if (backoff == 0L) 150 else backoff)
            }
        }
    }
}

private const val NBEST = 5

/** Optional: lets Settings show the last thing the wake listener heard (memory only, for troubleshooting). */
interface WakeTranscriptSink {
    fun onHeard(text: String)
}

/** Finds the wake word in a transcript and returns what was said after it ("" if nothing). Null = no wake word. */
object WakePhrase {
    private val names = listOf("jarvis", "jarvas", "jervis", "javis", "jarves", "jarvish", "jarbis")
    val greetings = setOf("hey", "hi", "hay", "ok", "okay", "hello", "yo")

    fun words(text: String): List<String> =
        text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** "jarvis" or a near spelling (edit distance 1 for 5-7 letter words, e.g. "jarvi", "jarviz"). */
    fun isName(w: String): Boolean = w in names || (w.length in 5..7 && w.startsWith("ja") && editDistance(w, "jarvis") <= 1)

    fun match(transcript: String, sensitivity: WakeSensitivity, learned: List<String> = emptyList()): String? {
        val ws = words(transcript)
        // Phrases learned from the user's voice, matched as whole word sequences.
        for (phrase in learned) {
            val p = words(phrase)
            if (p.isEmpty()) continue
            if (p.size == 1 && sensitivity == WakeSensitivity.STRICT) continue // single words only off Strict
            val at = indexOfSequence(ws, p)
            if (at >= 0) return ws.drop(at + p.size).joinToString(" ")
        }
        val i = ws.indexOfFirst { isName(it) }
        if (i < 0) return null
        // Strict: "Jarvis" must follow a greeting ("hey jarvis"), to avoid waking when someone just mentions the name.
        if (sensitivity == WakeSensitivity.STRICT && (i == 0 || ws[i - 1] !in greetings)) return null
        return ws.drop(i + 1).joinToString(" ")
    }

    /** First match across the recognizer's guesses (the top guess first). */
    fun matchAny(transcripts: List<String>, sensitivity: WakeSensitivity, learned: List<String> = emptyList()): String? =
        transcripts.asSequence().mapNotNull { match(it, sensitivity, learned) }.firstOrNull()

    private fun indexOfSequence(ws: List<String>, p: List<String>): Int {
        for (i in 0..ws.size - p.size) if (ws.subList(i, i + p.size) == p) return i
        return -1
    }

    fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val cur = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return d[b.length]
    }
}

/**
 * "Teach JARVIS your voice": from what the phone heard during training (all guesses per attempt), keeps the
 * phrasings that didn't already contain "Jarvis" so they wake JARVIS in future. Phrases are short (1-3 words)
 * and stored whole, so "hey service" wakes JARVIS but "customer service" doesn't.
 */
object WakeTraining {
    const val MAX_PHRASES = 20

    /** [attempts]: for each training utterance, the recognizer's guesses (best first). */
    fun learn(attempts: List<List<String>>, existing: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet(existing.map { WakePhrase.words(it).joinToString(" ") })
        for (guesses in attempts) {
            for (g in guesses.take(5)) {
                val ws = WakePhrase.words(g)
                if (ws.isEmpty() || ws.size > 3) continue
                if (ws.any { WakePhrase.isName(it) }) continue // already understood
                if (ws.size == 1 && ws[0] in WakePhrase.greetings) continue // just "hey"
                out += ws.joinToString(" ")
            }
        }
        return out.toList().takeLast(MAX_PHRASES)
    }

    /** How many attempts the phone already understood as "Jarvis" (top guess). */
    fun understood(attempts: List<List<String>>): Int =
        attempts.count { it.firstOrNull()?.let { t -> WakePhrase.words(t).any(WakePhrase::isName) } == true }
}
