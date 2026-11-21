package dev.jarvis.security

/**
 * Speaker verification for the spoken-PIN path (CLAUDE.md section 6: "spoken PIN + voice match").
 * Runs entirely on the phone. The voiceprint (an embedding vector, not audio) is stored encrypted in
 * [SecretStore] and never leaves the device. Audio samples are 16 kHz mono floats in [-1, 1].
 */
interface VoiceMatcher {
    suspend fun isEnrolled(): Boolean

    /**
     * Saves the user's voiceprint from a few short recordings. Needs a fresh fingerprint/device credential,
     * like changing the PIN, so someone holding the unlocked phone can't enrol their own voice.
     */
    suspend fun enroll(recordings: List<FloatArray>, proof: StrongAuthProof): Boolean

    /** True if [recording] sounds like the enrolled user. False when not enrolled. */
    suspend fun matches(recording: FloatArray): Boolean

    suspend fun remove(proof: StrongAuthProof)
}

/** Result of capturing a spoken PIN: the recognised digits (on-device) and whether the voice matched. */
/** [similarity] = voice-match score (0..1, numbers only) when a voice check ran; logged for calibration. */
data class SpokenPin(val digits: String?, val voiceMatched: Boolean, val similarity: Float? = null)
