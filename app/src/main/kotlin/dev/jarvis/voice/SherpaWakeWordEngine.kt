package dev.jarvis.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Hey Jarvis" detection with sherpa-onnx keyword spotting (Apache-2.0 code and model; docs/decisions.md D21).
 * A 3.3M-parameter streaming zipformer runs fully on the phone on one CPU thread; audio never leaves the device.
 * The mic is held only while [detections] is collected, so SpeechRecognizer can take over for the conversation.
 */
class SherpaWakeWordEngine(private val context: Context) : WakeWordEngine {
    override val name = "sherpa-onnx keyword spotting (on-device)"

    override fun isReady(): Boolean =
        runCatching { context.assets.list(DIR)?.toSet()?.containsAll(REQUIRED) == true }.getOrDefault(false)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the service before starting.
    override fun detections(sensitivity: WakeSensitivity, diagnostics: WakeDiagnostics?, mic: MicSource): Flow<WakeDetection> = callbackFlow {
        val spotter = KeywordSpotter(context.assets, config())
        // Per-stream keyword with this sensitivity's boost and threshold (same syntax as keywords.txt).
        val stream = spotter.createStream(keywords(sensitivity))
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            mic.androidSource, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK * 4),
        )
        // The reading loop owns the native resources and frees them when it ends.
        val loop = launch(Dispatchers.Default) {
            try {
                // Phone-side gain where available, then our own (many phones deliver raw, very quiet audio here).
                val hwGain = if (AutomaticGainControl.isAvailable()) {
                    runCatching { AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
                } else null
                record.startRecording()
                val pcm = ShortArray(CHUNK)
                val agc = AutoGain()
                val gate = sensitivity.gateMinLevel?.let { EnergyGate(minLevel = it) }
                try {
                    while (isActive) {
                        val n = record.read(pcm, 0, pcm.size)
                        if (n <= 0) continue
                        val raw = FloatArray(n) { pcm[it] / 32768f }
                        val level = EnergyGate.rms(raw)
                        val boosted = agc.apply(raw, level)
                        if (gate == null) {
                            // SENSITIVE: no silence gate, the model always listens.
                            stream.acceptWaveform(boosted, SAMPLE_RATE)
                            diagnostics?.onAudio(level, true, agc.gain)
                        } else {
                            // Standby: the gate decides on raw loudness; the model hears the boosted audio.
                            val gated = gate.process(boosted, level)
                            gated.feed.forEach { stream.acceptWaveform(it, SAMPLE_RATE) }
                            diagnostics?.onAudio(level, gate.open, agc.gain)
                            if (gated.closed) { spotter.reset(stream); continue }
                            if (gated.feed.isEmpty()) continue
                        }
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val keyword = spotter.getResult(stream).keyword
                        if (keyword.isNotBlank()) {
                            spotter.reset(stream) // start fresh so one utterance fires once
                            trySend(WakeDetection(keyword, System.currentTimeMillis()))
                        }
                    }
                    }
                } finally {
                    runCatching { hwGain?.release() }
                }
            } finally {
                runCatching { record.release() }
                stream.release()
                spotter.release()
            }
        }
        awaitClose {
            loop.cancel()
            runCatching { record.stop() } // unblocks a pending read so the loop can finish
        }
    }

    override fun spot(samples: FloatArray, sensitivity: WakeSensitivity): String? {
        val spotter = KeywordSpotter(context.assets, config())
        val stream = spotter.createStream(keywords(sensitivity))
        try {
            // Same framing as live use: a little silence, the recording, then silence to let the model finish.
            val padded = FloatArray(SAMPLE_RATE / 2) + samples + FloatArray(SAMPLE_RATE * 3 / 2)
            var i = 0
            while (i < padded.size) {
                val end = minOf(i + CHUNK, padded.size)
                stream.acceptWaveform(padded.copyOfRange(i, end), SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val k = spotter.getResult(stream).keyword
                    if (k.isNotBlank()) return k
                }
                i = end
            }
            return null
        } finally {
            stream.release()
            spotter.release()
        }
    }

    private fun config() = KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$DIR/encoder.int8.onnx",
                decoder = "$DIR/decoder.int8.onnx",
                joiner = "$DIR/joiner.int8.onnx",
            ),
            tokens = "$DIR/tokens.txt",
            numThreads = 1,
            provider = "cpu",
            modelType = "zipformer2",
        ),
        maxActivePaths = 4,
        keywordsFile = "$DIR/keywords.txt",
        keywordsScore = 1.5f,
        keywordsThreshold = 0.25f,
        numTrailingBlanks = 1,
    )

    private companion object {
        const val DIR = "kws"
        /**
         * Accepted phrasings, in the model's BPE pieces (checked with its bpe.model). Variants cover accents
         * and fast speech: hey / hi / hay / ok, and "Jah-vis" (soft R) and "Jarves".
         * Tested on the PC: all hit, and near-misses ("jars", "Javier", "harvest") don't.
         */
        val PHRASES = listOf(
            "\u2581HE Y \u2581JA R VI S" to "HEY_JARVIS",
            "\u2581HI \u2581JA R VI S" to "HI_JARVIS",
            "\u2581HA Y \u2581JA R VI S" to "HAY_JARVIS",
            "\u2581O K \u2581JA R VI S" to "OK_JARVIS",
            "\u2581HE Y \u2581JA VI S" to "HEY_JAVIS",
            "\u2581HE Y \u2581JA R VE S" to "HEY_JARVES",
        )

        fun keywords(s: WakeSensitivity): String =
            PHRASES.joinToString("/") { (pieces, label) -> "$pieces :${s.boost} #${s.threshold} @$label" }
        const val SAMPLE_RATE = 16_000
        /** 100 ms of audio per read. */
        const val CHUNK = 1_600
        val REQUIRED = setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "keywords.txt")
    }
}
