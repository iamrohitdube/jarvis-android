package dev.jarvis.voice

import kotlin.math.sqrt

/**
 * Battery saver for the always-on wake word: while the room is quiet only the loudness of each audio chunk
 * is measured (a few multiplications); the keyword model runs only while someone is talking.
 *
 * - Speech = loudness above max([minLevel], 3x the learned background noise).
 * - The noise floor is learned slowly, only while it's quiet, so a steady fan or engine doesn't keep the
 *   model awake.
 * - A short pre-roll ([preRollChunks]) is replayed when speech starts, so the start of "Hey" isn't lost.
 * - After [hangoverChunks] quiet chunks, the gate closes and asks the detector to reset its stream.
 */
class EnergyGate(
    private val minLevel: Float = 0.004f,
    private val hangoverChunks: Int = 15,
    private val preRollChunks: Int = 3,
) {
    data class Result(val feed: List<FloatArray>, val closed: Boolean)

    var open = false
        private set
    var noiseFloor = minLevel
        private set
    private var hang = 0
    private val preRoll = ArrayDeque<FloatArray>()

    /** [level] defaults to the chunk's loudness; pass the raw level when [chunk] has been boosted. */
    fun process(chunk: FloatArray, level: Float = rms(chunk)): Result {
        val speech = level > maxOf(minLevel, noiseFloor * 3f)
        if (!open) {
            if (speech) {
                open = true
                hang = hangoverChunks
                val feed = preRoll.toList() + listOf(chunk)
                preRoll.clear()
                return Result(feed, closed = false)
            }
            noiseFloor = 0.95f * noiseFloor + 0.05f * maxOf(level, minLevel / 2f)
            preRoll.addLast(chunk)
            while (preRoll.size > preRollChunks) preRoll.removeFirst()
            return Result(emptyList(), closed = false)
        }
        hang = if (speech) hangoverChunks else hang - 1
        if (hang <= 0) {
            open = false
            return Result(listOf(chunk), closed = true)
        }
        return Result(listOf(chunk), closed = false)
    }

    companion object {
        fun rms(x: FloatArray): Float {
            if (x.isEmpty()) return 0f
            var sum = 0.0
            for (v in x) sum += v * v
            return sqrt(sum / x.size).toFloat()
        }
    }
}
