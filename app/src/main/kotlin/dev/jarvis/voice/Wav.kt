package dev.jarvis.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16 kHz mono 16-bit PCM WAV writer (for the user-initiated "Save recording" diagnostic). */
object Wav {
    fun encode16k(samples: FloatArray, sampleRate: Int = 16_000): ByteArray {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1) // PCM
        buf.putShort(1); buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataBytes)
        for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return buf.array()
    }
}
