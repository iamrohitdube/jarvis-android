package dev.jarvis.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes an audio file (m4a, mp3, amr, ogg… whatever Android can decode) to 16 kHz mono floats, handing it over
 * in chunks so a long call never sits in memory whole. Used for call-recording transcripts (D30), on the phone.
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000

    /**
     * Streams [uri] as [chunkSeconds]-second pieces to [onChunk]. Stops after [maxMs] of audio.
     * Returns the decoded duration in ms (capped at [maxMs]).
     */
    suspend fun decode(
        context: Context, uri: Uri, chunkSeconds: Int = 20, maxMs: Long = 60 * 60_000L,
        onChunk: suspend (FloatArray) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio in this file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }

            val chunkSize = TARGET_RATE * chunkSeconds
            val buffer = FloatArray(chunkSize)
            var filled = 0
            var total = 0L
            val maxSamples = TARGET_RATE * maxMs / 1000
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && total < maxSamples) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val bytes = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                            bytes.position(info.offset); bytes.limit(info.offset + info.size)
                            val mono = if (floatPcm) {
                                val f = bytes.asFloatBuffer(); val all = FloatArray(f.remaining()); f.get(all); Resampler.downmix(all, channels)
                            } else {
                                val s = bytes.asShortBuffer(); val all = FloatArray(s.remaining()) { s.get() / 32768f }; Resampler.downmix(all, channels)
                            }
                            for (x in Resampler.linear(mono, rate, TARGET_RATE)) {
                                buffer[filled++] = x
                                total++
                                if (filled == chunkSize) { onChunk(buffer.copyOf()); filled = 0 }
                                if (total >= maxSamples) break
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (filled > TARGET_RATE / 2) onChunk(buffer.copyOf(filled))
            total * 1000 / TARGET_RATE
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

/** Plain sample-rate conversion and down-mixing (good enough for speech recognition). */
object Resampler {
    fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return FloatArray(frames) { f -> var sum = 0f; for (c in 0 until channels) sum += interleaved[f * channels + c]; sum / channels }
    }

    fun linear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outSize = (input.size.toLong() * toRate / fromRate).toInt()
        val step = fromRate.toDouble() / toRate
        return FloatArray(outSize) { i ->
            val pos = i * step
            val j = pos.toInt()
            val frac = (pos - j).toFloat()
            val a = input[j.coerceAtMost(input.lastIndex)]
            val b = input[(j + 1).coerceAtMost(input.lastIndex)]
            a + (b - a) * frac
        }
    }
}
