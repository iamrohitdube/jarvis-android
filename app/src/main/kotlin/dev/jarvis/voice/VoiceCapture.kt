package dev.jarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Waits for the user to speak on JARVIS's own microphone (silent, unlike the phone recognizer, which plays a sound
 * every time a listening session starts and ends) and returns what they said as 16 kHz PCM, or null if they said
 * nothing within the wait. The recording is then transcribed once ([Stt.transcribe]).
 */
fun interface VoiceCapture {
    suspend fun capture(waitMs: Long, onLevel: (Float) -> Unit): ShortArray?
}

class MicVoiceCapture(private val source: suspend () -> MicSource) : VoiceCapture {
    @SuppressLint("MissingPermission") // only used in voice flows that already hold RECORD_AUDIO
    override suspend fun capture(waitMs: Long, onLevel: (Float) -> Unit): ShortArray? = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(source().androidSource, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 1280 * 8))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return@withContext null }
        try {
            record.startRecording()
            // Room level is learnt from the first moments (ambient starts high and follows the quietest chunks).
            val c = FollowUpCapture(speechLevel = 0.002f, initialAmbient = 1f, skipMs = 0, waitMs = waitMs.toInt(), silenceMs = 800, maxMs = 12_000)
            val pcm = ShortArray(1280)
            while (!c.done) {
                ensureActive()
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) break
                val chunk = pcm.copyOf(n)
                c.offer(chunk)
                onLevel(EnergyGate.rms(FloatArray(n) { chunk[it] / 32768f }) * 10f)
            }
            c.audio()
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
