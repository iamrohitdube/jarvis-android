package dev.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** How JARVIS acknowledges "Hey Jarvis" (Settings → Hey Jarvis → Wake sound). */
enum class WakeAck(val label: String) {
    OFF("Silent"),
    CHIME("Chime"),
    HUM("Hum"),
    VOICE("Say a phrase"),
    CHIME_VOICE("Chime + phrase"),
}

enum class CueSound { CHIME, HUM }

/** What to play for one wake: a sound, a spoken phrase, both, or nothing. */
data class AckPlan(val sound: CueSound?, val phrase: String?) {
    companion object {
        /**
         * [preheard]: the question came in the same breath ("Hey Jarvis, what's the time?"), so no phrase is
         * spoken (only the sound) and the answer follows straight away. Phrases are also skipped when spoken
         * replies are off (quiet mode); a chime plays instead so the wake is still acknowledged.
         */
        fun of(mode: WakeAck, phrase: String, preheard: Boolean, speakReplies: Boolean): AckPlan {
            val say = phrase.trim().takeIf { it.isNotEmpty() && speakReplies && !preheard }
            return when (mode) {
                WakeAck.OFF -> AckPlan(null, null)
                WakeAck.CHIME -> AckPlan(CueSound.CHIME, null)
                WakeAck.HUM -> AckPlan(CueSound.HUM, null)
                WakeAck.VOICE -> if (say != null) AckPlan(null, say) else AckPlan(CueSound.CHIME, null)
                WakeAck.CHIME_VOICE -> AckPlan(CueSound.CHIME, say)
            }
        }
    }
}

/** Plays the wake acknowledgement and returns when it has finished (so the mic doesn't hear it). */
fun interface WakeCue {
    suspend fun play(plan: AckPlan, tts: Tts)
}

/** Sounds are synthesised on the phone (no audio files): a soft two-note chime or a low "mm-hm". */
class AndroidWakeCue(context: Context) : WakeCue {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val assistant = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    /** Pocket mode on a Bluetooth headset uses the call route; play the cue there so it reaches the helmet/earbuds. */
    private val call = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override suspend fun play(plan: AckPlan, tts: Tts) {
        plan.sound?.let { playSound(it) }
        plan.phrase?.let { p ->
            tts.speak(p)
            withTimeoutOrNull(4_000) {
                tts.speaking.first { it }
                tts.speaking.first { !it }
            }
        }
        if (plan.sound != null || plan.phrase != null) delay(120) // let the echo die before listening
    }

    private suspend fun playSound(sound: CueSound) = withContext(Dispatchers.IO) {
        val pcm = when (sound) { CueSound.CHIME -> CueSynth.chime(); CueSound.HUM -> CueSynth.hum() }
        val attributes = if (audio?.mode == AudioManager.MODE_IN_COMMUNICATION) call else assistant
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        audio?.requestAudioFocus(focus)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CueSynth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull()
        try {
            if (track != null) {
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(pcm.size * 1000L / CueSynth.RATE + 40)
            }
        } finally {
            runCatching { track?.stop() }
            track?.release()
            audio?.abandonAudioFocusRequest(focus)
        }
    }
}

/** Pure sound synthesis (unit-testable). */
object CueSynth {
    const val RATE = 22_050

    /** Two rising bell-like notes (E6 then B6), about 0.33 s. */
    fun chime(): ShortArray = concat(note(1318.5, 0.13, decay = 18.0), note(1975.5, 0.20, decay = 12.0))

    /** A soft low "mm-hm": two gliding hums, about 0.45 s. */
    fun hum(): ShortArray = concat(glide(196.0, 220.0, 0.16), silence(0.05), glide(220.0, 262.0, 0.24))

    private fun note(freq: Double, secs: Double, decay: Double): ShortArray {
        val n = (RATE * secs).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val attack = (t / 0.005).coerceAtMost(1.0)
            val release = ((n - i).toDouble() / (RATE * 0.01)).coerceAtMost(1.0) // no click at the end
            val env = attack * release * exp(-decay * t)
            val v = sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t)
            (v * env * 0.32 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun glide(from: Double, to: Double, secs: Double): ShortArray {
        val n = (RATE * secs).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val x = i.toDouble() / n
            phase += 2 * PI * (from + (to - from) * x) / RATE
            val env = sin(PI * x) // smooth in and out
            val v = sin(phase) + 0.4 * sin(2 * phase) + 0.15 * sin(3 * phase)
            (v * env * 0.30 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun silence(secs: Double) = ShortArray((RATE * secs).toInt())

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }
}
