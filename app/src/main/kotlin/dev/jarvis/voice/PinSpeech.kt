package dev.jarvis.voice

/**
 * Turns a recognised spoken PIN into digits: "two five eight zero", "2580", "two-five eight oh",
 * "double five one nine", "my PIN is 2 5 8 0", "I said 2580", "2580 please".
 * Words before the digits start ("so", "I said") and polite words after them ("please", "thanks") are ignored.
 * Anything else once the digits have started makes it null, so a misheard sentence is treated as
 * "didn't catch that" (no lockout penalty) rather than as a wrong PIN. The result is never logged or sent anywhere.
 */
object PinSpeech {
    private val words = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "nought" to "0", "naught" to "0",
        "one" to "1", "won" to "1",
        "two" to "2", "to" to "2", "too" to "2",
        "three" to "3", "tree" to "3",
        "four" to "4", "for" to "4", "fore" to "4",
        "five" to "5",
        "six" to "6",
        "seven" to "7",
        "eight" to "8", "ate" to "8",
        "nine" to "9", "niner" to "9",
    )
    /** Words that sound like digits but are usually just words ("oh, it's…", "I want to…"). */
    private val homophones = setOf("oh", "o", "won", "to", "too", "tree", "for", "fore", "ate")
    private val repeats = mapOf("double" to 2, "triple" to 3)
    private val filler = setOf("my", "pin", "is", "it's", "its", "the", "code", "number", "uh", "um", "and", "okay", "ok", "jarvis")
    private val trailing = setOf("please", "thanks", "thank", "you", "that's", "thats", "it", "done")

    fun digits(text: String): String? {
        val tokens = text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        fun digitLike(t: String?) = t != null && (t.all { it.isDigit() } || t in words || t in repeats)
        val out = StringBuilder()
        var repeat = 1
        var started = false
        var ended = false
        for ((i, t) in tokens.withIndex()) {
            when {
                ended -> if (t !in trailing && t !in filler) return null
                // Before the digits: a homophone not followed by a digit is just a word ("oh, my PIN is…").
                !started && t in homophones && !digitLike(tokens.getOrNull(i + 1)) -> Unit
                t.all { it.isDigit() } -> { out.append(if (repeat > 1 && t.length == 1) t.repeat(repeat) else t); repeat = 1; started = true }
                t in words -> { out.append(words.getValue(t).repeat(repeat)); repeat = 1; started = true }
                t in repeats -> repeat = repeats.getValue(t)
                t in filler -> Unit
                started && t in trailing -> ended = true
                !started -> Unit // lead-in words ("so", "I said")
                else -> return null
            }
        }
        return out.toString().takeIf { it.length in 4..8 }
    }
}
