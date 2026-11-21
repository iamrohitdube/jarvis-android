package dev.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A model file fetched on demand, pinned by SHA-256 (docs/decisions.md D23). */
data class ModelFile(val path: String, val url: String, val sha256: String, val bytes: Long)

/**
 * Optional on-device models for the spoken PIN (about 70 MB, downloaded only when the user turns it on):
 * - voice match: 3D-Speaker ERes2Net (English, VoxCeleb), Apache-2.0, from the sherpa-onnx release;
 * - digit recognition: Moonshine tiny English v2 (quantized), MIT, the same files as the sherpa-onnx release
 *   archive, fetched individually from the author's Hugging Face repo at a pinned commit.
 * Hashes were verified against the official sherpa-onnx GitHub release on 2026-11-21.
 */
object VoiceModels {
    private const val HF = "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/resolve/d1e6c30921780b8508d04b492dfb3ce8a51605d4"

    val SPEAKER = ModelFile(
        "speaker/eres2net_en.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
        "c4947caf150b8502fdd75bbe4089b971ab1ec42e90ee3ba547faca2cde44d9ee", 26_485_264,
    )
    val MOON_ENCODER = ModelFile("moonshine/encoder_model.ort", "$HF/encoder_model.ort",
        "94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221", 13_281_600)
    val MOON_DECODER = ModelFile("moonshine/decoder_model_merged.ort", "$HF/decoder_model_merged.ort",
        "cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0", 30_412_256)
    val MOON_TOKENS = ModelFile("moonshine/tokens.txt", "$HF/tokens.txt",
        "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049", 549_350)

    val ALL = listOf(SPEAKER, MOON_ENCODER, MOON_DECODER, MOON_TOKENS)
    val totalBytes: Long = ALL.sumOf { it.bytes }
}

/** Download progress, in bytes. */
data class DownloadProgress(val done: Long, val total: Long, val finished: Boolean = false)

/**
 * Downloads [VoiceModels] into no-backup app storage. Each file is streamed to a temporary file, its
 * SHA-256 is checked against the pinned value, and only then moved into place; a mismatch deletes it.
 */
class ModelDownloader(context: Context, private val http: OkHttpClient) {
    val dir = File(context.noBackupFilesDir, "voice-models")

    fun file(m: ModelFile) = File(dir, m.path)

    /** Cheap check (size); integrity was verified when each file was saved. */
    fun installed(): Boolean = VoiceModels.ALL.all { file(it).length() == it.bytes }

    fun download(): Flow<DownloadProgress> = flow {
        var done = VoiceModels.ALL.filter { file(it).length() == it.bytes }.sumOf { it.bytes }
        emit(DownloadProgress(done, VoiceModels.totalBytes))
        for (m in VoiceModels.ALL) {
            val target = file(m)
            if (target.length() == m.bytes) continue
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            val md = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(m.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
                val body = resp.body
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var sinceEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            sinceEmit += n
                            if (sinceEmit > 512 * 1024) { emit(DownloadProgress(done, VoiceModels.totalBytes)); sinceEmit = 0 }
                        }
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != m.sha256) {
                tmp.delete()
                throw IOException("Integrity check failed for ${m.path}")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't save ${m.path}")
        }
        emit(DownloadProgress(VoiceModels.totalBytes, VoiceModels.totalBytes, finished = true))
    }.flowOn(Dispatchers.IO)

    fun delete() { dir.deleteRecursively() }
}
