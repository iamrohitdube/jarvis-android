package dev.jarvis.voice

/**
 * Turns streamed reply text into speakable sentences as soon as each one is complete, so TTS can start
 * talking before the whole reply has arrived (CLAUDE.md section 8: speak sentence by sentence).
 *
 * A sentence ends at . ! ? or a newline followed by whitespace, except inside numbers ("12.4 lakh"),
 * after common abbreviations ("e.g.", "Rs.", "vs.") and after single initials ("J. Smith").
 */
class SentenceChunker(private val maxChars: Int = DEFAULT_MAX_CHARS) {
    private val buffer = StringBuilder()

    /** Adds streamed text; returns any sentences that are now complete (already cleaned for speech). */
    fun feed(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val end = findBoundary() ?: break
            val sentence = buffer.substring(0, end)
            buffer.delete(0, end)
            out += split(SpeechText.clean(sentence))
        }
        // Very long text with no punctuation: speak it in chunks rather than waiting forever.
        if (buffer.length > maxChars) {
            val cut = buffer.lastIndexOf(" ", maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            out += split(SpeechText.clean(buffer.substring(0, cut)))
            buffer.delete(0, cut)
        }
        return out.filter { it.isNotBlank() }
    }

    /** End of reply: whatever is left is the last sentence. */
    fun flush(): List<String> {
        val rest = SpeechText.clean(buffer.toString())
        buffer.clear()
        return split(rest).filter { it.isNotBlank() }
    }

    fun reset() = buffer.clear()

    /** Index just past a sentence end that is followed by whitespace (so we know the next sentence began). */
    private fun findBoundary(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '\n' && i > 0) return i + 1
            if (c != '.' && c != '!' && c != '?') continue
            val next = buffer.getOrNull(i + 1) ?: return null // need to see what follows
            // Allow closing quotes/brackets after the punctuation.
            var j = i + 1
            while (j < buffer.length && buffer[j] in "\"')]”’") j++
            val after = buffer.getOrNull(j) ?: return null
            if (!after.isWhitespace()) continue
            if (c == '.' && isNotSentenceEnd(i, next)) continue
            return j
        }
        return null
    }

    private fun isNotSentenceEnd(dot: Int, next: Char): Boolean {
        if (next.isDigit()) return true // 12.4
        val word = buffer.substring(0, dot).takeLastWhile { !it.isWhitespace() }.lowercase().trimStart('(', '"')
        if (word.length == 1 && word[0].isLetter()) return true // initials: "J. Smith"
        return word in ABBREVIATIONS
    }

    private fun split(text: String): List<String> {
        if (text.length <= maxChars) return listOf(text)
        return text.chunked(maxChars)
    }

    companion object {
        /** TextToSpeech.getMaxSpeechInputLength() is 4000 on current Android; stay well under it. */
        const val DEFAULT_MAX_CHARS = 1500
        private val ABBREVIATIONS = setOf(
            "e.g", "i.e", "etc", "vs", "mr", "mrs", "ms", "dr", "st", "rs", "no", "approx", "inc", "ltd", "jan", "feb",
            "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "fig",
        )
    }
}

/** Makes reply text sound right when spoken: drops markdown and symbols TTS would read out literally. */
object SpeechText {
    private val url = Regex("https?://\\S+")
    private val markdownLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|`+|~~)")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val bullet = Regex("(?m)^\\s*([-*•]|\\d+[.)])\\s+")
    private val spaces = Regex("\\s+")

    fun clean(text: String): String = text
        .replace(markdownLink, "$1")
        .replace(url, "link")
        .replace(heading, "")
        .replace(bullet, "")
        .replace(emphasis, "")
        .replace("|", ", ")
        .replace(spaces, " ")
        .trim()
}

/** Understands a spoken answer to "PIN or fingerprint?". Null = unclear (the default, fingerprint, applies). */
object SpokenChoice {
    enum class Choice { PIN, FINGERPRINT }

    /** First clear answer among the recognizer's guesses (best first). */
    fun parseAny(guesses: List<String>): Choice? = guesses.asSequence().mapNotNull { parse(it) }.firstOrNull()

    fun parse(transcript: String): Choice? {
        val t = " " + transcript.lowercase().replace(Regex("[^a-z ]"), " ") + " "
        val pin = listOf(" pin ", " pins ", " ping ", " peen ", " pen ", " bin ", " p i n ", " pin number ", " code ", " passcode ").any { it in t }
        val finger = listOf("finger", "biometric", "thumb", "touch").any { it in t }
        return when {
            pin && !finger -> Choice.PIN
            finger && !pin -> Choice.FINGERPRINT
            else -> null
        }
    }
}
