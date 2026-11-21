package dev.jarvis.voice

/**
 * Turns a recording into text, all on the phone: the phone's own recognizer first, then Moonshine (downloaded with
 * the spoken-PIN models) if the recognizer can't (on the user's phone it sometimes answers every request with
 * "no speech", 2026-11-21). Null = nothing usable.
 */
fun interface Transcriber {
    suspend fun transcribe(pcm: ShortArray, preferOnDevice: Boolean): String?
}

class FallbackTranscriber(private val stt: Stt, private val moonshine: VoicePinEngine) : Transcriber {
    override suspend fun transcribe(pcm: ShortArray, preferOnDevice: Boolean): String? {
        val phone = runCatching { stt.transcribe(pcm, preferOnDevice) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (phone != null) { dlog("transcribe: phone recognizer"); return phone }
        val local = runCatching { moonshine.transcribe(FloatArray(pcm.size) { pcm[it] / 32768f }) }.getOrNull()
        dlog("transcribe: phone recognizer failed, Moonshine ${if (local != null) "ok" else "unavailable/empty"}")
        return local
    }
}
