package dev.jarvis.voice

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A yes/no by voice with no screen ("Hey Jarvis" in the pocket or on the helmet): say [question], wait for it to
 * finish, listen once, and let [approves] judge the answer. Silence, noise or a timeout is a no.
 */
class SpokenConfirm(
    private val tts: Tts,
    private val stt: Stt,
    private val preferOnDevice: suspend () -> Boolean,
    private val listenTimeoutMs: Long = 8_000,
) {
    suspend fun ask(question: String, approves: (String) -> Boolean): Boolean {
        tts.speak(question)
        withTimeoutOrNull(2_000) { tts.speaking.first { it } }
        withTimeoutOrNull(20_000) { tts.speaking.first { !it } }
        val heard = withTimeoutOrNull(listenTimeoutMs) {
            stt.listen(preferOnDevice()).firstOrNull { it is SttEvent.Final || it is SttEvent.Failed }
        }
        val text = (heard as? SttEvent.Final)?.text.orEmpty()
        return text.isNotBlank() && approves(text)
    }
}
