package dev.jarvis.voice

/**
 * Software automatic gain for microphones that deliver raw, very quiet audio (common with the
 * VOICE_RECOGNITION source, which skips the phone's own gain). Tracks the recent speech peak (decaying over
 * ~3 s) and scales audio so speech lands near [target], up to [maxGain]x, never attenuating.
 *
 * Measured on the PC with the app's wake-word model: speech at RMS 0.0008-0.003 went from 2/18 detected to
 * 16/18, with no new false triggers (docs/decisions.md D24).
 */
class AutoGain(
    private val target: Float = 0.05f,
    private val maxGain: Float = 40f,
    private val floor: Float = 0.0004f,
) {
    private var peak = floor

    /** Current multiplier (for diagnostics). */
    var gain = 1f
        private set

    /** Returns a scaled, clipped copy of [chunk]; [level] is the chunk's raw RMS. */
    fun apply(chunk: FloatArray, level: Float = EnergyGate.rms(chunk)): FloatArray {
        peak = maxOf(level, peak * 0.97f)
        gain = (target / maxOf(peak, floor)).coerceIn(1f, maxGain)
        return FloatArray(chunk.size) { (chunk[it] * gain).coerceIn(-1f, 1f) }
    }

    companion object {
        /** One-shot normalisation of a whole recording (used for Test now and the spoken PIN). */
        fun normalize(audio: FloatArray, target: Float = 0.05f, maxGain: Float = 60f): Pair<FloatArray, Float> {
            var peak = 0f
            var i = 0
            while (i < audio.size) {
                val end = minOf(i + 1600, audio.size)
                peak = maxOf(peak, EnergyGate.rms(audio.copyOfRange(i, end)))
                i = end
            }
            val g = (target / maxOf(peak, 0.0002f)).coerceIn(1f, maxGain)
            return FloatArray(audio.size) { (audio[it] * g).coerceIn(-1f, 1f) } to g
        }
    }
}
