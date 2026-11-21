package dev.jarvis.voice

import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Which microphone path JARVIS records from for the wake word and the spoken PIN. Phones differ a lot: on some,
 * VOICE_RECOGNITION picks a mic or processing that sounds badly muffled (seen on the user's phone: almost no
 * energy above 1 kHz). "Compare microphones" in Settings tests each and picks the best (docs/decisions.md D24).
 */
enum class MicSource(val androidSource: Int, val label: String) {
    STANDARD(MediaRecorder.AudioSource.MIC, "standard mic"),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "voice-recognition mic"),
    CALL(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "call mic"),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, "video mic"),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "unprocessed mic");

    companion object {
        /** The sources worth trying on this phone (UNPROCESSED only if the phone says it supports it). */
        fun candidates(audio: AudioManager?): List<MicSource> {
            val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            return entries.filter { it != UNPROCESSED || unprocessed }
        }
    }
}
