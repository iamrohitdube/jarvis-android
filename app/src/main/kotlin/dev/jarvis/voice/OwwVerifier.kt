package dev.jarvis.voice

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Personal "Hey Jarvis" check (docs/decisions.md D27), the same idea as openWakeWord's custom verifier models:
 * a logistic regression over the model's own features (the last 16 x 96 embeddings), trained on the phone from a
 * few recordings of the user saying "Hey Jarvis" plus some of their ordinary speech.
 *
 * With it, a wake needs the base model to be at least slightly interested ([candidateThreshold], set from the
 * user's own recordings, so their accent can score low) AND this check to say "that's the user saying it".
 * That catches the user's quieter or accented "Hey Jarvis" and rejects other voices, TV and rhymes ("Hey Travis").
 * Only numbers derived from the recordings are stored (no audio), in app-private no-backup storage.
 */
class OwwVerifier(
    private val mean: FloatArray,
    private val std: FloatArray,
    private val weights: FloatArray,
    private val bias: Float,
    val candidateThreshold: Float,
) {
    /** Probability (0..1) that [features] (16 x 96, oldest first) is the user saying the wake phrase. */
    fun probability(features: List<FloatArray>): Float {
        var z = bias.toDouble()
        var k = 0
        for (frame in features) for (v in frame) { z += weights[k] * ((v - mean[k]) / std[k]); k++ }
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC); out.writeInt(weights.size); out.writeFloat(bias); out.writeFloat(candidateThreshold)
            for (a in listOf(mean, std, weights)) a.forEach(out::writeFloat)
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val DIM = OwwStream.FRAMES * 96
        private const val MAGIC = 0x4F575631 // "OWV1"

        fun load(file: File): OwwVerifier? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                require(inp.readInt() == MAGIC)
                val n = inp.readInt(); require(n == DIM)
                val bias = inp.readFloat(); val cand = inp.readFloat()
                val read = { FloatArray(n) { inp.readFloat() } }
                OwwVerifier(read(), read(), read(), bias, cand)
            }
        }.getOrNull()

        fun flatten(features: List<FloatArray>): FloatArray {
            val out = FloatArray(DIM); var k = 0
            for (f in features) { f.copyInto(out, k); k += f.size }
            return out
        }
    }
}

/** Trains [OwwVerifier]: standardise, then class-balanced L2 logistic regression by gradient descent. */
object OwwVerifierTrainer {
    const val MIN_CANDIDATE = 0.05f
    data class Result(val verifier: OwwVerifier, val positivesAccepted: Int, val positives: Int, val negativesRejected: Int, val negatives: Int)

    /**
     * [positives]/[negatives]: flattened 16x96 feature windows. [userPeakScores]: the base model's best score on each
     * of the user's "Hey Jarvis" recordings, used to set how low a base score may be and still be checked.
     * [l2]: regularisation strength (openWakeWord uses sklearn C=0.001, i.e. strong; the default here is comparable
     * for the mean loss used).
     */
    fun train(
        positives: List<FloatArray>,
        negatives: List<FloatArray>,
        userPeakScores: List<Float>,
        l2: Double = 0.3,
        iterations: Int = 400,
    ): Result {
        require(positives.isNotEmpty() && negatives.isNotEmpty())
        val d = positives[0].size
        val all = positives + negatives
        val mean = FloatArray(d); val std = FloatArray(d)
        for (x in all) for (j in 0 until d) mean[j] += x[j]
        for (j in 0 until d) mean[j] /= all.size
        for (x in all) for (j in 0 until d) { val t = x[j] - mean[j]; std[j] += t * t }
        for (j in 0 until d) std[j] = sqrt(std[j] / all.size).coerceAtLeast(1e-3f)
        val xs = all.map { x -> DoubleArray(d) { j -> ((x[j] - mean[j]) / std[j]).toDouble() } }
        val ys = DoubleArray(all.size) { if (it < positives.size) 1.0 else 0.0 }
        // Balanced classes: the few positives count as much as the many negatives.
        val wPos = all.size / (2.0 * positives.size); val wNeg = all.size / (2.0 * negatives.size)
        val sw = DoubleArray(all.size) { if (ys[it] == 1.0) wPos else wNeg }
        val w = DoubleArray(d); var b = 0.0
        // Step size from the largest curvature of the (weighted) data, by power iteration: 1 / (0.25 * lambda_max + l2).
        val lambdaMax = run {
            var v = DoubleArray(d) { 1.0 / sqrt(d.toDouble()) }
            var lambda = 1.0
            repeat(25) {
                val u = DoubleArray(xs.size) { i -> var z = 0.0; val x = xs[i]; for (j in 0 until d) z += x[j] * v[j]; z * sw[i] }
                val next = DoubleArray(d)
                for (i in xs.indices) { val x = xs[i]; val c = u[i] / xs.size; for (j in 0 until d) next[j] += c * x[j] }
                lambda = sqrt(next.sumOf { it * it }).coerceAtLeast(1e-9)
                v = DoubleArray(d) { next[it] / lambda }
            }
            lambda
        }
        val lr = 1.0 / (0.25 * lambdaMax * 1.1 + l2)
        val grad = DoubleArray(d)
        repeat(iterations) {
            grad.fill(0.0); var gb = 0.0
            for (i in xs.indices) {
                val x = xs[i]
                var z = b; for (j in 0 until d) z += w[j] * x[j]
                val p = 1.0 / (1.0 + exp(-z))
                val e = sw[i] * (p - ys[i]) / xs.size
                for (j in 0 until d) grad[j] += e * x[j]
                gb += e
            }
            for (j in 0 until d) w[j] -= lr * (grad[j] + l2 * w[j])
            b -= lr * gb
        }
        // How low may the base model score and still be checked: half the user's weakest "Hey Jarvis", within limits
        // (at least 0.05, so near-zero scores from ordinary chat never reach the check).
        val candidate = ((userPeakScores.minOrNull() ?: 0.1f) * 0.5f).coerceIn(MIN_CANDIDATE, 0.3f)
        val v = OwwVerifier(mean, std, FloatArray(d) { w[it].toFloat() }, b.toFloat(), candidate)
        fun p(x: FloatArray) = v.probability(listOf(x)) // flattened = one "frame" of DIM values
        val acc = positives.count { p(it) >= 0.5f }
        val rej = negatives.count { p(it) < 0.5f }
        return Result(v, acc, positives.size, rej, negatives.size)
    }

    /** Mean log-loss, for tests. */
    fun logLoss(v: OwwVerifier, xs: List<FloatArray>, ys: List<Boolean>): Double =
        xs.indices.sumOf { i ->
            val p = v.probability(listOf(xs[i])).toDouble().coerceIn(1e-7, 1 - 1e-7)
            -(if (ys[i]) ln(p) else ln(1 - p))
        } / xs.size
}

/**
 * The personal check's training examples, kept as feature numbers (never audio) so it can be retrained when the user
 * marks a false wake ("Not for me"), without recording again. App-private no-backup storage.
 */
class OwwTrainingSet(
    val positives: MutableList<FloatArray> = ArrayList(),
    val negatives: MutableList<FloatArray> = ArrayList(),
    /** Moments that woke JARVIS by mistake: weighted 3x in training. */
    val hardNegatives: MutableList<FloatArray> = ArrayList(),
    val peaks: MutableList<Float> = ArrayList(),
) {
    fun fit(): OwwVerifierTrainer.Result {
        val negs = negatives + hardNegatives + hardNegatives + hardNegatives
        return OwwVerifierTrainer.train(positives, negs, peaks)
    }

    fun addFalseWake(windows: List<FloatArray>) {
        hardNegatives += windows
        while (hardNegatives.size > MAX_HARD) hardNegatives.removeAt(0)
    }

    /** Keeps training fast: at most [MAX_NEG] ordinary negatives (an even sample). */
    fun trim() {
        if (negatives.size > MAX_NEG) {
            val step = negatives.size.toDouble() / MAX_NEG
            val kept = List(MAX_NEG) { negatives[(it * step).toInt()] }
            negatives.clear(); negatives += kept
        }
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            for (list in listOf(positives, negatives, hardNegatives)) {
                out.writeInt(list.size); list.forEach { v -> v.forEach(out::writeFloat) }
            }
            out.writeInt(peaks.size); peaks.forEach(out::writeFloat)
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private const val MAGIC = 0x4F575431 // "OWT1"
        const val MAX_NEG = 1_200
        const val MAX_HARD = 200

        fun load(file: File): OwwTrainingSet? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                require(inp.readInt() == MAGIC)
                fun list(): MutableList<FloatArray> = MutableList(inp.readInt()) { FloatArray(OwwVerifier.DIM) { inp.readFloat() } }
                val p = list(); val n = list(); val h = list()
                val peaks = MutableList(inp.readInt()) { inp.readFloat() }
                OwwTrainingSet(p, n, h, peaks)
            }
        }.getOrNull()
    }
}
